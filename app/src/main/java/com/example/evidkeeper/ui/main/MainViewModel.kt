package com.example.evidkeeper.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val storageManager: StorageManager) : ViewModel() {

    /**
     * 首帧直接用缓存（如有）立即渲染，随后异步扫描补全。
     * 原来构造里同步 load()，SAF 全量扫描跑在主线程，这就是首页打开卡的主因。
     */
    private val _items = MutableStateFlow(storageManager.peekCachedItems() ?: emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** 首次加载是否还没完成（空列表 + 加载中 = 显示进度而不是"还没有事项"） */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 过滤后的列表：关键词匹配名称或文件名，忽略大小写 */
    val filteredItems: StateFlow<List<Item>> = combine(items, query) { list, q ->
        val kw = q.trim()
        if (kw.isEmpty()) list
        else list.filter { item ->
            item.meta.name.contains(kw, ignoreCase = true) ||
                    item.files.any { it.name?.contains(kw, ignoreCase = true) == true }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load()
    }

    /**
     * 重新扫描事项列表。
     * 扫描走 IO 线程；默认吃缓存，避免每次 ON_RESUME 都全量重扫。
     */
    fun load(forceRescan: Boolean = false) {
        viewModelScope.launch {
            val list = storageManager.listItems(forceRescan)
            _items.value = list
            _loading.value = false
        }
    }

    /** 搜索词变更 */
    fun onQueryChange(q: String) {
        _query.value = q
    }

    /** 改名；完成后回调结果并刷新列表 */
    fun renameItem(item: Item, newName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = storageManager.renameItem(item, newName)
            if (ok) load()
            onResult(ok)
        }
    }

    /** 删除事项；完成后回调结果并刷新列表 */
    fun deleteItem(item: Item, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = storageManager.deleteItem(item)
            if (ok) load()
            onResult(ok)
        }
    }
}
