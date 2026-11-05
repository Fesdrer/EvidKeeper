package com.example.evidkeeper.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evidkeeper.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _rootUri = MutableStateFlow<Uri?>(null)
    val rootUri: StateFlow<Uri?> = _rootUri.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow("glm-4.6v-flash")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _aiPrompt = MutableStateFlow("")
    val aiPrompt: StateFlow<String> = _aiPrompt.asStateFlow()

    init { load() }

    /**
     * 从持久存储读取当前值到草稿。
     * 加密 prefs 首次访问会走 Android Keystore，必须离开主线程。
     */
    fun load() {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { settingsStore.getRootUri() }
            val key = withContext(Dispatchers.IO) { settingsStore.getApiKey() ?: "" }
            val model = withContext(Dispatchers.IO) { settingsStore.getModel() }
            val prompt = withContext(Dispatchers.IO) { settingsStore.getAiPrompt() }

            _rootUri.value = uri
            _apiKey.value = key
            _model.value = model
            _aiPrompt.value = prompt
        }
    }

    /** 写入持久存储；完成回调用于弹"已保存" */
    fun save(onSaved: () -> Unit = {}) {
        val uri = _rootUri.value
        val key = _apiKey.value
        val model = _model.value
        val prompt = _aiPrompt.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uri?.let { settingsStore.saveRootUri(it) }
                settingsStore.saveApiKey(key)
                settingsStore.saveModel(model)
                settingsStore.saveAiPrompt(prompt)
            }
            onSaved()
        }
    }

    fun setRootUri(uri: Uri) { _rootUri.value = uri }
    fun setApiKey(key: String) { _apiKey.value = key }
    fun setModel(model: String) { _model.value = model }
    fun setAiPrompt(prompt: String) { _aiPrompt.value = prompt }

    /** 恢复默认：只改草稿，需点保存才持久化（与 draft 模式一致） */
    fun resetAiPrompt() { _aiPrompt.value = SettingsStore.DEFAULT_NAME_PROMPT }
}
