package com.example.evidkeeper.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailViewModel(
    private val storageManager: StorageManager,
    private val itemId: Int,
) : ViewModel() {

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    // 文件操作结果提示
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // 批量添加文件进行中
    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    init { load() }

    fun load() {
        _item.value = storageManager.listItems().find { it.meta.id == itemId }
    }

    fun clearToast() { _toastMessage.value = null }

    // ── 星标切换 ──
    fun toggleImportant() {
        val it = _item.value ?: return
        storageManager.toggleImportant(it)
        load()
    }

    // ── 改名 ──
    fun renameItem(newName: String): Boolean {
        val it = _item.value ?: return false
        val ok = storageManager.renameItem(it, newName)
        if (ok) load()
        return ok
    }

    // ── 改时间 ──
    fun changeTime(year: Int, month: Int) {
        val it = _item.value ?: return
        storageManager.changeTime(it, year, month)
        load()
    }

    // ── 添加文件（支持多选批量） ──
    fun addFiles(uris: List<Uri>) {
        val it = _item.value
        if (it == null) {
            _toastMessage.value = "事项不存在"
            return
        }
        if (uris.isEmpty() || _isAdding.value) return

        _isAdding.value = true
        viewModelScope.launch {
            var success = 0
            var failed = 0
            var needCleanup = false
            // 后台逐文件复制，避免大文件阻塞主线程
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val r = storageManager.addFile(it, uri)
                    if (r.success) {
                        success++
                        if (r.needPromptDeleteSource) needCleanup = true
                    } else {
                        failed++
                    }
                }
            }
            load() // 只刷新一次，避免 N 次全目录扫描
            _isAdding.value = false
            _toastMessage.value = when {
                failed == 0 && !needCleanup -> "已添加 $success 个文件"
                failed == 0 -> "已添加 $success 个文件，但部分原文件无法删除，请手动清理"
                success == 0 -> "添加失败：$failed 个文件未能复制"
                else -> "已添加 $success 个，$failed 个失败"
            }
        }
    }

    // ── 删除文件 ──
    fun deleteFile(fileName: String): Boolean {
        val it = _item.value ?: return false
        val file = it.files.find { f -> f.name == fileName } ?: return false
        val ok = storageManager.deleteFile(it, file)
        if (ok) load()
        return ok
    }

    // ── 文件改名 ──
    fun renameFile(oldName: String, newMainName: String): Boolean {
        val it = _item.value ?: return false
        val file = it.files.find { f -> f.name == oldName } ?: return false
        val ok = storageManager.renameFile(it, file, newMainName)
        if (ok) load()
        return ok
    }
}
