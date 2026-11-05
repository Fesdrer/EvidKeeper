package com.example.evidkeeper.ui.create

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.settings.SettingsStore
import com.example.evidkeeper.data.storage.StorageManager
import com.example.evidkeeper.network.GlmApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

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
        if (_isGenerating.value) return

        _isGenerating.value = true
        viewModelScope.launch {
            // 读加密 prefs 也可能触达 Keystore，放 IO 线程
            val apiKey = withContext(Dispatchers.IO) { settingsStore.getApiKey() }
            if (apiKey.isNullOrBlank()) {
                _aiError.value = "请先到设置页配置 API Key"
                _isGenerating.value = false
                return@launch
            }
            val model = withContext(Dispatchers.IO) { settingsStore.getModel() }
            val prompt = withContext(Dispatchers.IO) { settingsStore.getAiPrompt() }

            val result = GlmApiClient.generateName(context, uri, apiKey, model, prompt)
            result.onSuccess { generated ->
                _name.value = generated
                _isGenerating.value = false
            }.onFailure { e ->
                _aiError.value = e.message ?: "生成失败"
                _isGenerating.value = false
            }
        }
    }

    /**
     * 创建事项。建目录、写 meta、复制文件全部是 SAF 跨进程调用，
     * 原来跑在点击回调（主线程）里，会卡住整帧；现在整体移到 IO 线程，
     * 并通过 onResult 回调把结果送回 UI。
     */
    fun create(onResult: (String?) -> Unit) {
        if (_isCreating.value) return

        val n = _name.value.trim()
        if (n.isEmpty()) {
            onResult("名称不能为空")
            return
        }
        val illegal = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        if (n.any { it in illegal }) {
            onResult("名称不能包含这些字符：\\ / : * ? \" < > |")
            return
        }

        _isCreating.value = true
        viewModelScope.launch {
            val item = storageManager.createItem(n, _year.value, _month.value)
            if (item == null) {
                _isCreating.value = false
                onResult("创建失败，请检查存储目录是否已设置")
                return@launch
            }

            var message: String? = null
            _selectedFileUri.value?.let { uri ->
                val result = storageManager.addFile(item, uri)
                if (result.needPromptDeleteSource) {
                    message = "已创建，但无法删除原文件，请手动清理"
                }
            }
            _isCreating.value = false
            _done.value = true
            onResult(message)
        }
    }

    fun resetDone() { _done.value = false }
}
