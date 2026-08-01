package com.example.evidkeeper.ui.create

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.evidkeeper.data.model.AddFileResult
import com.example.evidkeeper.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class CreateViewModel(private val storageManager: StorageManager) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    // 默认当前年月
    private val cal = Calendar.getInstance()
    private val _year = MutableStateFlow(cal.get(Calendar.YEAR))
    val year: StateFlow<Int> = _year.asStateFlow()
    private val _month = MutableStateFlow(cal.get(Calendar.MONTH) + 1)
    val month: StateFlow<Int> = _month.asStateFlow()

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()
    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun setName(name: String) { _name.value = name }
    fun setTime(year: Int, month: Int) { _year.value = year; _month.value = month }
    fun setSelectedFile(uri: Uri?, fileName: String?) {
        _selectedFileUri.value = uri
        _selectedFileName.value = fileName
    }

    /** 创建事项，成功则设置 done=true */
    fun create(): String? {
        val n = _name.value.trim()
        if (n.isEmpty()) return "名称不能为空"
        val illegal = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        if (n.any { it in illegal }) return "名称不能包含这些字符：\\ / : * ? \" < > |"

        val item = storageManager.createItem(n, _year.value, _month.value)
            ?: return "创建失败，请检查存储目录是否已设置"

        // 如果选了文件则移入
        _selectedFileUri.value?.let { uri ->
            val result = storageManager.addFile(item, uri)
            if (result.needPromptDeleteSource) {
                _done.value = true
                return "已创建，但无法删除原文件，请手动清理"
            }
        }
        _done.value = true
        return null // 成功
    }

    fun resetDone() { _done.value = false }
}
