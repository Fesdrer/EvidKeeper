package com.example.evidkeeper.ui.create

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.settings.SettingsStore
import com.example.evidkeeper.data.storage.StorageManager
import com.example.evidkeeper.network.GlmApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class CreateViewModel(
    private val storageManager: StorageManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

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

    // AI 生成状态
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    fun setName(name: String) { _name.value = name }
    fun setTime(year: Int, month: Int) { _year.value = year; _month.value = month }
    fun setSelectedFile(uri: Uri?, fileName: String?) {
        _selectedFileUri.value = uri
        _selectedFileName.value = fileName
    }

    fun clearAiError() { _aiError.value = null }

    /** AI 生成名称 */
    fun generateName(context: Context) {
        val uri = _selectedFileUri.value
        if (uri == null) {
            _aiError.value = "请先选择文件"
            return
        }
        val apiKey = settingsStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            _aiError.value = "请先到设置页配置 API Key"
            return
        }
        val model = settingsStore.getModel()

        _isGenerating.value = true
        viewModelScope.launch {
            val result = GlmApiClient.generateName(context, uri, apiKey, model)
            result.onSuccess { generated ->
                _name.value = generated
                _isGenerating.value = false
            }.onFailure { e ->
                _aiError.value = e.message ?: "生成失败"
                _isGenerating.value = false
            }
        }
    }

    /** 创建事项，成功则设置 done=true */
    fun create(): String? {
        val n = _name.value.trim()
        if (n.isEmpty()) return "名称不能为空"
        val illegal = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        if (n.any { it in illegal }) return "名称不能包含这些字符：\\ / : * ? \" < > |"

        val item = storageManager.createItem(n, _year.value, _month.value)
            ?: return "创建失败，请检查存储目录是否已设置"

        _selectedFileUri.value?.let { uri ->
            val result = storageManager.addFile(item, uri)
            if (result.needPromptDeleteSource) {
                _done.value = true
                return "已创建，但无法删除原文件，请手动清理"
            }
        }
        _done.value = true
        return null
    }

    fun resetDone() { _done.value = false }
}
