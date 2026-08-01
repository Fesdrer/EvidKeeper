package com.example.evidkeeper.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.evidkeeper.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _rootUri = MutableStateFlow<Uri?>(null)
    val rootUri: StateFlow<Uri?> = _rootUri.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow("glm-4.6v-flash")
    val model: StateFlow<String> = _model.asStateFlow()

    init { load() }

    /** 从持久存储读取当前值到草稿 */
    fun load() {
        _rootUri.value = settingsStore.getRootUri()
        _apiKey.value = settingsStore.getApiKey() ?: ""
        _model.value = settingsStore.getModel()
    }

    /** 写入持久存储 */
    fun save() {
        _rootUri.value?.let { settingsStore.saveRootUri(it) }
        settingsStore.saveApiKey(_apiKey.value)
        settingsStore.saveModel(_model.value)
    }

    fun setRootUri(uri: Uri) { _rootUri.value = uri }
    fun setApiKey(key: String) { _apiKey.value = key }
    fun setModel(model: String) { _model.value = model }
}
