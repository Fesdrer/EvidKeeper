package com.example.evidkeeper.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.evidkeeper.data.model.AddFileResult
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DetailViewModel(
    private val storageManager: StorageManager,
    private val itemId: Int,
) : ViewModel() {

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    // 文件操作结果提示
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

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

    // ── 添加文件 ──
    fun addFile(uri: Uri): AddFileResult {
        val it = _item.value ?: return AddFileResult(false, errorMessage = "事项不存在")
        val result = storageManager.addFile(it, uri)
        if (result.success) {
            load()
            if (result.needPromptDeleteSource) {
                _toastMessage.value = "已复制，但无法删除原文件，请手动清理"
            }
        }
        return result
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
