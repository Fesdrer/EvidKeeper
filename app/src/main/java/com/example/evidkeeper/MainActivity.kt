package com.example.evidkeeper

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.evidkeeper.data.settings.SettingsStore
import com.example.evidkeeper.data.storage.StorageManager
import com.example.evidkeeper.ui.theme.EvidKeeperTheme

class MainActivity : ComponentActivity() {

    private val settingsStore by lazy { SettingsStore(this) }
    private val storageManager by lazy { StorageManager(this, settingsStore) }

    // SAF 目录选择
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化授权
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsStore.saveRootUri(uri)
            Log.d(TAG, "存储目录已设置: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EvidKeeperTheme {
                MainScreen(
                    onSelectFolder = { selectFolderLauncher.launch(null) },
                    onTestStorage = { runStorageTest() },
                )
            }
        }
    }

    // ── 临时：存储层验证（步骤 3 会替换掉） ──

    private fun runStorageTest() {
        Log.d(TAG, "========== 存储层验证开始 ==========")

        val rootUri = settingsStore.getRootUri()
        if (rootUri == null) {
            Log.e(TAG, "请先选择存储目录！")
            return
        }

        val sm = storageManager

        // 1. 创建 3 个事项
        Log.d(TAG, "--- 创建事项 ---")
        val item1 = sm.createItem("房租", 2026, 8)
        Log.d(TAG, "创建 item1: $item1")

        val item2 = sm.createItem("水电费", 2026, 7)
        Log.d(TAG, "创建 item2: $item2")

        val item3 = sm.createItem("网费", 2026, 8)
        Log.d(TAG, "创建 item3: $item3")

        // 2. 打印扫描结果
        Log.d(TAG, "--- 扫描事项列表 ---")
        val items = sm.listItems()
        items.forEach { item ->
            Log.d(TAG, "  ${item.meta.id}: ${item.meta.name} " +
                    "(${item.meta.year}-${"%02d".format(item.meta.month)}) " +
                    "重要=${item.meta.important} 文件数=${item.files.size}")
        }

        // 3. 编号分配
        Log.d(TAG, "--- 编号分配 ---")
        val nextId = sm.nextId()
        Log.d(TAG, "下一个可用编号: $nextId")

        // 4. 改名
        Log.d(TAG, "--- 改名 ---")
        if (item1 != null) {
            val renamed = sm.renameItem(item1, "新房租")
            Log.d(TAG, "改名结果: $renamed")
            // 改名后重新扫描获取最新引用
            val afterRename = sm.listItems().find { it.meta.id == item1.meta.id }
            Log.d(TAG, "改名后: ${afterRename?.meta?.name} → 文件夹: ${afterRename?.folder?.name}")
        }

        // 5. 切换重要
        Log.d(TAG, "--- 切换重要 ---")
        if (item2 != null) {
            sm.toggleImportant(item2)
            val afterToggle = sm.listItems().find { it.meta.id == item2.meta.id }
            Log.d(TAG, "切换后 item2 重要=${afterToggle?.meta?.important}")
        }

        // 6. 删除第一个事项
        Log.d(TAG, "--- 删除事项 ---")
        val toDelete = sm.listItems().find { it.meta.id == item1?.meta?.id }
        if (toDelete != null) {
            val deleted = sm.deleteItem(toDelete)
            Log.d(TAG, "删除 ${toDelete.meta.name} 结果: $deleted")
        }

        // 7. 打印最终列表
        Log.d(TAG, "--- 最终列表 ---")
        val finalItems = sm.listItems()
        finalItems.forEach { item ->
            Log.d(TAG, "  ${item.meta.id}: ${item.meta.name} 重要=${item.meta.important}")
        }

        // 8. 检查 remove 目录
        Log.d(TAG, "--- remove 目录内容 ---")
        val rootDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, rootUri)
        val removeDir = rootDir?.findFile("remove")
        if (removeDir != null) {
            removeDir.listFiles().forEach { f ->
                Log.d(TAG, "  remove/${f.name}")
            }
        } else {
            Log.d(TAG, "  remove 目录为空或不存在")
        }

        Log.d(TAG, "========== 存储层验证结束 ==========")
    }

    companion object {
        private const val TAG = "EvidKeeper"
    }
}

@Composable
fun MainScreen(
    onSelectFolder: () -> Unit,
    onTestStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "EvidKeeper")

            Button(onClick = onSelectFolder) {
                Text("选择存储目录")
            }

            Button(onClick = onTestStorage) {
                Text("测试存储层")
            }
        }
    }
}
