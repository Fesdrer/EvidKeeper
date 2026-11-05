package com.example.evidkeeper.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    private val storageManager: StorageManager,
    private val itemId: Int,
) : ViewModel() {

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    /** 首次加载中（区分"事项不存在"和"还没读完"） */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // 文件操作结果提示
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // 批量添加文件进行中
    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    init { load() }

    /**
     * 刷新当前事项。
     * 走 StorageManager 的共享缓存，进详情页不再全量重扫所有事项的 meta。
     */
    fun load(forceRescan: Boolean = false) {
        viewModelScope.launch {
            // SAF 权限被撤销 / provider 异常会抛，不能让它把 loading 永久卡在 true
            try {
                _item.value = storageManager.listItems(forceRescan).find { it.meta.id == itemId }
            } catch (e: Exception) {
                _toastMessage.value = "读取失败：${e.message ?: "存储目录不可用"}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun clearToast() { _toastMessage.value = null }

    // ── 星标切换 ──
    fun toggleImportant() {
        val it = _item.value ?: return
        viewModelScope.launch {
            storageManager.toggleImportant(it)
            load()
        }
    }

    // ── 改名 ──
    fun renameItem(newName: String, onResult: (Boolean) -> Unit) {
        val it = _item.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = storageManager.renameItem(it, newName)
            if (ok) load()
            onResult(ok)
        }
    }

    // ── 改时间 ──
    fun changeTime(year: Int, month: Int) {
        val it = _item.value ?: return
        viewModelScope.launch {
            storageManager.changeTime(it, year, month)
            load()
        }
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
            // 逐文件复制。StorageManager 内部已切 Dispatchers.IO，不会再阻塞主线程。
            uris.forEach { uri ->
                val r = storageManager.addFile(it, uri)
                if (r.success) {
                    success++
                    if (r.needPromptDeleteSource) needCleanup = true
                } else {
                    failed++
                }
            }
            load(forceRescan = true) // 只刷新一次，避免 N 次全目录扫描
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
    fun deleteFile(fileName: String, onResult: (Boolean) -> Unit = {}) {
        val it = _item.value ?: return
        val file = it.files.find { f -> f.name == fileName } ?: return
        viewModelScope.launch {
            val ok = storageManager.deleteFile(it, file)
            if (ok) load()
            onResult(ok)
        }
    }

    // ── 文件改名 ──
    fun renameFile(oldName: String, newMainName: String, onResult: (Boolean) -> Unit) {
        val it = _item.value ?: return onResult(false)
        val file = it.files.find { f -> f.name == oldName } ?: return onResult(false)
        viewModelScope.launch {
            val ok = storageManager.renameFile(it, file, newMainName)
            if (ok) load()
            onResult(ok)
        }
    }
}
