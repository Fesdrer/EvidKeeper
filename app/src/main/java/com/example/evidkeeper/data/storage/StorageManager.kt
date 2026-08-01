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
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 全部 SAF 文件操作。基于用户选择的存储根目录（DocumentFile.fromTreeUri）。
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

    // ── 非法字符（Windows 文件系统不允许） ──
    private val illegalCharsRegex = Regex("""[\\/:*?"<>|]""")

    // ═══════════════════════════════════════════════════════════════
    // 私有辅助方法
    // ═══════════════════════════════════════════════════════════════

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

    /** 读取事项文件夹内的 .meta.json，失败返回容错默认值 */
    private fun readMeta(folder: DocumentFile): ItemMeta {
        val metaFile = folder.findFile(META_FILE_NAME) ?: return fallbackMeta(folder)
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

    /** 写入 .meta.json 到事项文件夹 */
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

    /** 列出一个文件夹内除 .meta.json 外的所有文件 */
    private fun listFilesInFolder(folder: DocumentFile): List<DocumentFile> {
        return folder.listFiles()
            .filter { it.isFile && it.name != META_FILE_NAME }
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
    // 公开接口
    // ═══════════════════════════════════════════════════════════════

    // ── 1. 扫描事项列表 ──

    /**
     * 扫描根目录，返回排序后的事项列表。
     * 排序：重要置顶 → 时间倒序 → 编号升序。
     */
    fun listItems(): List<Item> {
        val root = getRootDir() ?: return emptyList()

        val items = mutableListOf<Item>()

        for (subDir in root.listFiles()) {
            if (!subDir.isDirectory) continue
            val dirName = subDir.name ?: continue

            // 跳过 remove 目录（不区分大小写）
            if (dirName.equals(REMOVE_DIR, ignoreCase = true)) continue

            // 只认含 .meta.json 的文件夹
            val metaFile = subDir.findFile(META_FILE_NAME)
            if (metaFile == null || !metaFile.isFile) continue

            val meta = readMeta(subDir)
            val files = listFilesInFolder(subDir)

            items.add(Item(meta = meta, folder = subDir, files = files))
        }

        return items.sortedWith(
            compareByDescending<Item> { it.meta.important }
                .thenByDescending { it.meta.year }
                .thenByDescending { it.meta.month }
                .thenBy { it.meta.id },
        )
    }

    // ── 2. 编号分配 ──

    /**
     * 从 1 开始取第一个未被使用的编号。
     * id=0（解析失败）的事项不参与分配。
     */
    fun nextId(): Int {
        val usedIds = listItems()
            .filter { it.meta.id > 0 }
            .map { it.meta.id }
            .toSet()
        var id = 1
        while (id in usedIds) id++
        return id
    }

    // ── 3. 创建事项 ──

    /**
     * 创建新事项：分配编号 → 建文件夹 → 写 .meta.json
     */
    fun createItem(name: String, year: Int, month: Int): Item? {
        val root = getRootDir() ?: return null
        val id = nextId()
        val safeName = sanitizeName(name)
        val folderName = "%03d-%s".format(id, safeName)

        val folder = root.createDirectory(folderName) ?: return null
        val meta = ItemMeta(id = id, name = safeName, year = year, month = month)

        if (!writeMeta(folder, meta)) {
            folder.delete()  // 回滚
            return null
        }

        return Item(meta = meta, folder = folder, files = emptyList())
    }

    // ── 4. 改名（事项） ──

    /**
     * 只改名称部分，编号不变。同步改文件夹名 + meta。
     * @return 新名称是否成功
     */
    fun renameItem(item: Item, newName: String): Boolean {
        val root = getRootDir() ?: return false
        val safeName = sanitizeName(newName)
        val newFolderName = "%03d-%s".format(item.meta.id, safeName)

        // 如果名称没变就直接成功
        if (newFolderName == item.folder.name) {
            if (item.meta.name != safeName) {
                writeMeta(item.folder, item.meta.copy(name = safeName))
            }
            return true
        }

        // 检查是否与已有文件夹撞名
        val existingNames = root.listFiles().mapNotNull { it.name }.toSet()
        if (newFolderName in existingNames) {
            Log.w(TAG, "改名失败：文件夹名冲突 $newFolderName")
            return false
        }

        // renameTo 返回 Boolean，成功后原 DocumentFile 内部 URI 已更新
        if (!item.folder.renameTo(newFolderName)) return false

        // 写入新 meta
        val updatedMeta = item.meta.copy(name = safeName)
        return writeMeta(item.folder, updatedMeta)
    }

    // ── 5. 移入文件 ──

    /**
     * 将手机上的文件复制进事项文件夹，成功后删除源文件。
     * 目标重名 → 自动加 (1) 后缀。
     */
    fun addFile(item: Item, sourceUri: Uri): AddFileResult {
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
            ?: return AddFileResult(
                success = false,
                errorMessage = "文件复制失败",
            )

        // 删除源文件
        val deleted = deleteSourceUri(sourceUri)

        return AddFileResult(
            success = true,
            newFile = newFile,
            needPromptDeleteSource = !deleted,
        )
    }

    // ── 6. 删除文件（移入 remove） ──

    /**
     * 把文件移入 remove/ 目录。
     * 优先用 DocumentsContract.moveDocument（原子操作），失败退回复制+删除。
     */
    fun deleteFile(item: Item, file: DocumentFile): Boolean {
        val removeDir = getRemoveDir() ?: return false
        val finalName = resolveRemoveCollision(removeDir, file.name ?: "file")

        // 先尝试原子移动（同 SAF 树内可用）
        if (tryMoveDocument(file.uri, item.folder.uri, removeDir.uri)) {
            // 移动后可能需要重命名
            val moved = removeDir.findFile(file.name ?: "file")
            if (moved != null && moved.name != finalName) {
                moved.renameTo(finalName)
            }
            return true
        }

        // 回退：复制 + 删除
        Log.w(TAG, "moveDocument 失败，回退到复制+删除: ${file.name}")
        val mimeType = file.type ?: context.contentResolver.getType(file.uri)
        val copied = copyFile(file.uri, removeDir, finalName, mimeType)
        if (copied == null) return false

        return file.delete().also { deleted ->
            if (!deleted) Log.w(TAG, "删除原文件失败（已复制到 remove）: ${file.name}")
        }
    }

    // ── 7. 删除事项（整个文件夹移入 remove） ──

    /**
     * 将事项文件夹整体移入 remove/。
     * 优先用 DocumentsContract.moveDocument（原子操作），失败退回复制+删除。
     */
    fun deleteItem(item: Item): Boolean {
        val removeDir = getRemoveDir() ?: return false
        val resolvedName = resolveRemoveCollision(removeDir, item.folder.name ?: "item")

        // 尝试用 moveDocument 移动（仅当 item 不在根目录直接下级时可走原子移动）
        // 根目录 URI 是 tree URI 格式，不能直接传给 moveDocument，所以这里一律走复制+删除
        // 复制整个文件夹
        Log.d(TAG, "删除事项: ${item.folder.name} → remove/$resolvedName")
        val newFolder = removeDir.createDirectory(resolvedName)
            ?: return false

        // 复制所有文件（包括 .meta.json）
        var allCopied = true
        for (file in item.folder.listFiles()) {
            if (file.isDirectory) continue  // 不处理嵌套目录
            val copied = copyFile(file.uri, newFolder, file.name ?: "file", file.type)
            if (copied == null) allCopied = false
        }

        if (!allCopied) {
            Log.w(TAG, "部分文件复制失败，保留原文件夹")
            return false
        }

        // 删除原文件夹
        item.folder.delete()
        return true
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

    // ── 8. 切换重要 ──

    /**
     * 切换事项的星标状态
     */
    fun toggleImportant(item: Item): Boolean {
        val updated = item.meta.copy(important = !item.meta.important)
        return writeMeta(item.folder, updated)
    }

    // ── 9. 时间修改 ──

    /**
     * 修改事项的年月
     */
    fun changeTime(item: Item, year: Int, month: Int): Boolean {
        val updated = item.meta.copy(year = year, month = month)
        return writeMeta(item.folder, updated)
    }

    // ── 10. 文件改名 ──

    /**
     * 修改文件名，保留扩展名。
     * 目标重名 → 自动加 (1) 后缀。
     */
    fun renameFile(item: Item, file: DocumentFile, newName: String): Boolean {
        val safeName = sanitizeName(newName)
        val oldName = file.name ?: return false
        val ext = oldName.substringAfterLast('.', "")
        val extWithDot = if (ext.isNotEmpty() && oldName.contains('.')) ".$ext" else ""

        // 保留原扩展名
        val finalName = if (safeName.endsWith(extWithDot, ignoreCase = true)) {
            safeName
        } else {
            safeName + extWithDot
        }

        // 防止与 .meta.json 冲突
        val mainName = finalName.substringBeforeLast('.')
        if (mainName.equals(META_FILE_NAME.substringBeforeLast('.'), ignoreCase = true)) {
            Log.w(TAG, "文件名不能与 .meta.json 冲突: $finalName")
            return false
        }

        // 目标重名处理
        val resolvedName = resolveNameCollision(item.folder, finalName)

        if (resolvedName == oldName) return true

        return file.renameTo(resolvedName)
    }

    companion object {
        private const val TAG = "StorageManager"
        private const val META_FILE_NAME = ".meta.json"
        private const val REMOVE_DIR = "remove"
    }
}
