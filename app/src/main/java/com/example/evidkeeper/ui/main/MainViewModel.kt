package com.example.evidkeeper.ui.main

import androidx.lifecycle.ViewModel
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(private val storageManager: StorageManager) : ViewModel() {

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    init {
        load()
    }

    /** 重新扫描事项列表 */
    fun load() {
        _items.value = storageManager.listItems()
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

    /**
     * 快速创建事项（临时，步骤 5 会被新建页替代）。
     * 使用当前年月。
     */
    fun quickCreate(name: String): Item? {
        val cal = java.util.Calendar.getInstance()
        val item = storageManager.createItem(
            name = name,
            year = cal.get(java.util.Calendar.YEAR),
            month = cal.get(java.util.Calendar.MONTH) + 1,
        )
        if (item != null) load()
        return item
    }
}
