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

class MainViewModel(private val storageManager: StorageManager) : ViewModel() {

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

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

    /** 重新扫描事项列表 */
    fun load() {
        _items.value = storageManager.listItems()
    }

    /** 搜索词变更 */
    fun onQueryChange(q: String) {
        _query.value = q
    }

    /** 改名，成功后刷新列表 */
    fun renameItem(item: Item, newName: String): Boolean {
        val ok = storageManager.renameItem(item, newName)
        if (ok) load()
        return ok
    }

    /** 删除事项，成功后刷新列表 */
    fun deleteItem(item: Item): Boolean {
        val ok = storageManager.deleteItem(item)
        if (ok) load()
        return ok
    }
}
