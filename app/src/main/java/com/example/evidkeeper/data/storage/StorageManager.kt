package com.example.evidkeeper.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.evidkeeper.data.model.AddFileResult
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.model.ItemMeta
import com.example.evidkeeper.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 全部 SAF 文件操作。基于用户选择的存储根目录（DocumentFile.fromTreeUri）。
 *
 * 性能约法（v1.2）：
 * 1. 所有公开方法都是 suspend，内部切 Dispatchers.IO。SAF 每次调用都是一次跨进程
 *    Binder 往返，主线程上做一次全目录扫描就会掉帧，必须离开主线程。
 * 2. 扫描结果进内存缓存 cachedItems。原来每次 ON_RESUME / 每次进详情页都全量重扫，
 *    现在只有显式 forceRescan=true 才重扫，其余走缓存。
 * 3. 扫描时每个事项只 listFiles() 一次，同时拿到 meta 和文件列表。原实现
 *    findFile(meta) + readMeta 内部再 findFile + listFilesInFolder = 4 次跨进程查询，
 *    现为 2 次。
 * 4. 所有写操作串行化（ioMutex），并在成功后失效缓存。
 */
class StorageManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val calendar: Calendar get() = Calendar.getInstance()

    /** 所有扫描与写操作串行化，避免并发操作同一棵 SAF 树 */
    private val ioMutex = Mutex()

    /** 上次扫描结果；null 表示无缓存 */
    @Volatile
    private var cachedItems: List<Item>? = null

    // ── 非法字符（Windows 文件系统不允许） ──
    private val illegalCharsRegex = Regex("""[\\/:*?"<>|]""")

    // ═══════════════════════════════════════════════════════════════
    // 私有辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 在 IO 线程 + 互斥锁内执行一段阻塞代码 */
    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) { ioMutex.withLock { block() } }

    /** 取缓存，没有就扫一次（调用方必须已持有 ioMutex） */
    private fun ensureLoaded(): List<Item> =
        cachedItems ?: scanItems().also { cachedItems = it }

    /** 数据变更后失效缓存 */
    private fun invalidate() {
        cachedItems = null
    }

    /** 获取根目录 DocumentFile，未授权则返回 null */
    private fun getRootDir(): DocumentFile? {
        val uri = settingsStore.getRootUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /** 获取 remove 目录，不存在则自动创建 */
    private fun getRemoveDir(): DocumentFile? {
        val root = getRootDir() ?: return null
        val existing = root.findFile(REMOVE_DIR)
        if (existing != null && existing.isDirectory) return existing
        return root.createDirectory(REMOVE_DIR)
    }

    /** 清洗名称中的非法字符 */
    private fun sanitizeName(name: String): String {
        return name.trim().replace(illegalCharsRegex, "_")
    }

    /** 从文件名解析编号前缀，如 "001-房租" → 1，解析失败 → 0 */
    private fun parseIdFromFolderName(folderName: String): Int {
        val match = Regex("""^(\d+)-""").find(folderName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 从文件夹名去掉编号前缀，如 "001-房租" → "房租"，无前缀则返原名 */
    private fun parseNameFromFolderName(folderName: String): String {
        val match = Regex("""^\d+-(.+)""").find(folderName)
        return match?.groupValues?.get(1) ?: folderName
    }

    /** 读取事项文件夹内的 meta.json，失败返回容错默认值 */
    private fun readMeta(metaFile: DocumentFile, folder: DocumentFile): ItemMeta {
        return try {
            context.contentResolver.openInputStream(metaFile.uri)?.use { stream ->
                json.decodeFromString<ItemMeta>(stream.bufferedReader().readText())
            } ?: fallbackMeta(folder)
        } catch (e: Exception) {
            Log.w(TAG, "读取 meta 失败: ${folder.name}", e)
            fallbackMeta(folder)
        }
    }

    /** 容错 meta：从文件夹名推断，时间取当前年月 */
    private fun fallbackMeta(folder: DocumentFile): ItemMeta {
        val folderName = folder.name ?: "unknown"
        return ItemMeta(
            id = parseIdFromFolderName(folderName),
            name = parseNameFromFolderName(folderName),
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            important = false,
        )
    }

    /** 写入 meta.json 到事项文件夹 */
    private fun writeMeta(folder: DocumentFile, meta: ItemMeta): Boolean {
        return try {
            // 删除旧 meta（存在则覆盖）
            folder.findFile(META_FILE_NAME)?.delete()
            val newFile = folder.createFile("application/json", META_FILE_NAME)
                ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(json.encodeToString(ItemMeta.serializer(), meta).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "写入 meta 失败: ${folder.name}", e)
            false
        }
    }

    /**
     * 处理重名：在扩展名前加 (1)、(2)…
     * 如 "截图.jpg" → "截图(1).jpg" → "截图(2).jpg"
     */
    private fun resolveNameCollision(
        parent: DocumentFile,
        desiredName: String,
    ): String {
        val existingNames = parent.listFiles().mapNotNull { it.name }.toSet()
        if (desiredName !in existingNames) return desiredName

        val dotIndex = desiredName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            val candidate = "$baseName($counter)$ext"
            if (candidate !in existingNames) return candidate
            counter++
        }
    }

    /** 获取当前日期字符串 yyyyMMdd，供 remove 重名使用 */
    private fun todaySuffix(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    /**
     * 在 remove 目录内解析重名：加 -yyyyMMdd 后缀
     */
    private fun resolveRemoveCollision(
        removeDir: DocumentFile,
        desiredName: String,
    ): String {
        val existingNames = removeDir.listFiles().mapNotNull { it.name }.toSet()
        if (desiredName !in existingNames) return desiredName

        val dotIndex = desiredName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""
        val suffix = todaySuffix()

        val candidate = "$baseName-$suffix$ext"
        // 如果加当天日期还撞名（极少见），追加 (1) 后缀
        if (candidate !in existingNames) return candidate
        var counter = 1
        while (true) {
            val c = "$baseName-$suffix($counter)$ext"
            if (c !in existingNames) return c
            counter++
        }
    }

    /**
     * 通过流复制文件
     * @return 新创建的文件 DocumentFile，失败返回 null
     */
    private fun copyFile(
        sourceUri: Uri,
        targetParent: DocumentFile,
        targetName: String,
        mimeType: String?,
    ): DocumentFile? {
        return try {
            val newFile = targetParent.createFile(mimeType ?: "application/octet-stream", targetName)
                ?: return null
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            newFile
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败: $targetName", e)
            null
        }
    }

    /**
     * 删除源文件（SAF / content URI）
     */
    private fun deleteSourceUri(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            try {
                context.contentResolver.delete(uri, null, null) > 0
            } catch (e2: Exception) {
                Log.w(TAG, "删除源文件失败: $uri", e2)
                false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 扫描
    // ═══════════════════════════════════════════════════════════════

    /**
     * 真正执行扫描。排序：重要置顶 → 时间倒序 → 编号倒序。
     * 调用方必须已持有 ioMutex。
     */
    private fun scanItems(): List<Item> {
        val root = getRootDir() ?: return emptyList()

        val items = mutableListOf<Item>()

        for (subDir in root.listFiles()) {
            if (!subDir.isDirectory) continue
            val dirName = subDir.name ?: continue

            // 跳过 remove 目录（不区分大小写）
            if (dirName.equals(REMOVE_DIR, ignoreCase = true)) continue

            // 一次 listFiles 同时拿到 meta 和文件列表（原来要 4 次跨进程查询）
            val children = subDir.listFiles()
            val metaFile = children.firstOrNull { it.name == META_FILE_NAME } ?: continue
            if (!metaFile.isFile) continue

            val files = children.filter { it.name != null && it.name != META_FILE_NAME && it.isFile }

            items.add(Item(meta = readMeta(metaFile, subDir), folder = subDir, files = files))
        }

        return items.sortedWith(
            compareByDescending<Item> { it.meta.important }
                .thenByDescending { it.meta.year }
                .thenByDescending { it.meta.month }
                .thenByDescending { it.meta.id },
        )
    }

    /** 同步取缓存，绝不触发 IO。给首帧立即渲染用。 */
    fun peekCachedItems(): List<Item>? = cachedItems

    /**
     * 扫描事项列表（挂起，内部在 IO 线程执行）。
     * @param forceRescan true 时无视缓存重新扫描
     */
    suspend fun listItems(forceRescan: Boolean = false): List<Item> = io {
        if (!forceRescan) ensureLoaded() else scanItems().also { cachedItems = it }
    }

    /** 从 1 开始取第一个未被使用的编号（调用方已持有 ioMutex） */
    private fun nextIdFrom(items: List<Item>): Int {
        val usedIds = items.asSequence().map { it.meta.id }.filter { it > 0 }.toSet()
        var id = 1
        while (id in usedIds) id++
        return id
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开写操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建新事项：分配编号 → 建文件夹 → 写 meta.json
     */
    suspend fun createItem(name: String, year: Int, month: Int): Item? = io {
        val root = getRootDir() ?: return@io null
        val id = nextIdFrom(ensureLoaded())
        val safeName = sanitizeName(name)
        val folderName = "%03d-%s".format(id, safeName)

        val folder = root.createDirectory(folderName) ?: return@io null
        val meta = ItemMeta(id = id, name = safeName, year = year, month = month)

        if (!writeMeta(folder, meta)) {
            folder.delete()  // 回滚
            return@io null
        }

        invalidate()
        Item(meta = meta, folder = folder, files = emptyList())
    }

    /**
     * 只改名称部分，编号不变。同步改文件夹名 + meta。
     * @return 新名称是否成功
     */
    suspend fun renameItem(item: Item, newName: String): Boolean = io {
        val root = getRootDir() ?: return@io false
        val safeName = sanitizeName(newName)
        val newFolderName = "%03d-%s".format(item.meta.id, safeName)

        // 如果名称没变就直接成功
        if (newFolderName == item.folder.name) {
            if (item.meta.name != safeName) {
                writeMeta(item.folder, item.meta.copy(name = safeName))
            }
            invalidate()
            return@io true
        }

        // 检查是否与已有文件夹撞名
        val existingNames = root.listFiles().mapNotNull { it.name }.toSet()
        if (newFolderName in existingNames) {
            Log.w(TAG, "改名失败：文件夹名冲突 $newFolderName")
            return@io false
        }

        // renameTo 返回 Boolean，成功后原 DocumentFile 内部 URI 已更新
        if (!item.folder.renameTo(newFolderName)) return@io false

        // 写入新 meta
        val updatedMeta = item.meta.copy(name = safeName)
        val ok = writeMeta(item.folder, updatedMeta)
        invalidate()
        ok
    }

    /**
     * 将手机上的文件复制进事项文件夹，成功后删除源文件。
     * 目标重名 → 自动加 (1) 后缀。
     */
    suspend fun addFile(item: Item, sourceUri: Uri): AddFileResult = io {
        val resolver = context.contentResolver

        // 获取源文件信息
        var displayName = "未命名文件"
        var mimeType: String? = null
        resolver.query(sourceUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                // 尝试获取 MIME（部分 provider 不返回）
                val typeIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.MIME_TYPE)
                if (typeIdx >= 0) mimeType = cursor.getString(typeIdx)
            }
        }
        if (mimeType == null) {
            mimeType = resolver.getType(sourceUri)
        }

        // 重名处理
        val finalName = resolveNameCollision(item.folder, displayName)

        // 复制文件
        val newFile = copyFile(sourceUri, item.folder, finalName, mimeType)
            ?: return@io AddFileResult(
                success = false,
                errorMessage = "文件复制失败",
            )

        // 删除源文件
        val deleted = deleteSourceUri(sourceUri)
        invalidate()

        AddFileResult(
            success = true,
            newFile = newFile,
            needPromptDeleteSource = !deleted,
        )
    }

    /**
     * 把文件移入 remove/ 目录。
     * 优先用 DocumentsContract.moveDocument（原子操作），失败退回复制+删除。
     */
    suspend fun deleteFile(item: Item, file: DocumentFile): Boolean = io {
        val removeDir = getRemoveDir() ?: return@io false
        val finalName = resolveRemoveCollision(removeDir, file.name ?: "file")

        // 先尝试原子移动（同 SAF 树内可用）
        if (tryMoveDocument(file.uri, item.folder.uri, removeDir.uri)) {
            // 移动后可能需要重命名
            val moved = removeDir.findFile(file.name ?: "file")
            if (moved != null && moved.name != finalName) {
                moved.renameTo(finalName)
            }
            invalidate()
            return@io true
        }

        // 回退：复制 + 删除
        Log.w(TAG, "moveDocument 失败，回退到复制+删除: ${file.name}")
        val mimeType = file.type ?: context.contentResolver.getType(file.uri)
        val copied = copyFile(file.uri, removeDir, finalName, mimeType)
        if (copied == null) return@io false

        val deleted = file.delete().also { ok ->
            if (!ok) Log.w(TAG, "删除原文件失败（已复制到 remove）: ${file.name}")
        }
        invalidate()
        deleted
    }

    /**
     * 将事项文件夹整体移入 remove/。
     * 优先用 DocumentsContract.moveDocument（原子操作），失败退回复制+删除。
     */
    suspend fun deleteItem(item: Item): Boolean = io {
        val removeDir = getRemoveDir() ?: return@io false
        val resolvedName = resolveRemoveCollision(removeDir, item.folder.name ?: "item")

        // 根目录 URI 是 tree URI 格式，不能直接传给 moveDocument，所以这里一律走复制+删除
        Log.d(TAG, "删除事项: ${item.folder.name} → remove/$resolvedName")
        val newFolder = removeDir.createDirectory(resolvedName) ?: return@io false

        // 复制所有文件（包括 meta.json）
        var allCopied = true
        for (file in item.folder.listFiles()) {
            if (file.isDirectory) continue  // 不处理嵌套目录
            val copied = copyFile(file.uri, newFolder, file.name ?: "file", file.type)
            if (copied == null) allCopied = false
        }

        if (!allCopied) {
            Log.w(TAG, "部分文件复制失败，保留原文件夹")
            return@io false
        }

        // 删除原文件夹
        item.folder.delete()
        invalidate()
        true
    }

    /**
     * 尝试用 DocumentsContract.moveDocument 原子移动文件/文件夹。
     * @return 是否移动成功
     */
    private fun tryMoveDocument(
        documentUri: Uri,
        sourceParentUri: Uri,
        targetParentUri: Uri,
    ): Boolean {
        return try {
            val result = DocumentsContract.moveDocument(
                context.contentResolver,
                documentUri,
                sourceParentUri,
                targetParentUri,
            )
            result != null
        } catch (e: Exception) {
            Log.w(TAG, "moveDocument 异常: ${e.message}")
            false
        }
    }

    /**
     * 切换事项的星标状态
     */
    suspend fun toggleImportant(item: Item): Boolean = io {
        val updated = item.meta.copy(important = !item.meta.important)
        writeMeta(item.folder, updated).also { invalidate() }
    }

    /**
     * 修改事项的年月
     */
    suspend fun changeTime(item: Item, year: Int, month: Int): Boolean = io {
        val updated = item.meta.copy(year = year, month = month)
        writeMeta(item.folder, updated).also { invalidate() }
    }

    /**
     * 修改文件名，保留扩展名。
     * 目标重名 → 自动加 (1) 后缀。
     */
    suspend fun renameFile(item: Item, file: DocumentFile, newName: String): Boolean = io {
        val safeName = sanitizeName(newName)
        val oldName = file.name ?: return@io false
        val ext = oldName.substringAfterLast('.', "")
        val extWithDot = if (ext.isNotEmpty() && oldName.contains('.')) ".$ext" else ""

        // 保留原扩展名
        val finalName = if (safeName.endsWith(extWithDot, ignoreCase = true)) {
            safeName
        } else {
            safeName + extWithDot
        }

        // 防止与 meta.json 冲突
        val mainName = finalName.substringBeforeLast('.')
        if (mainName.equals(META_FILE_NAME.substringBeforeLast('.'), ignoreCase = true)) {
            Log.w(TAG, "文件名不能与 meta.json 冲突: $finalName")
            return@io false
        }

        // 目标重名处理
        val resolvedName = resolveNameCollision(item.folder, finalName)

        if (resolvedName == oldName) return@io true

        file.renameTo(resolvedName).also { invalidate() }
    }

    companion object {
        private const val TAG = "StorageManager"
        private const val META_FILE_NAME = "meta.json"
        private const val REMOVE_DIR = "remove"
    }
}
