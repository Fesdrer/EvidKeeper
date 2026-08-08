package com.example.evidkeeper.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 持久化存储：SAF 目录 Uri、智谱 API Key、AI 模型名。
 * 使用 EncryptedSharedPreferences 加密存储 API Key。
 */
class SettingsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ── 存储目录 Uri ──

    fun getRootUri(): Uri? {
        val str = prefs.getString(KEY_ROOT_URI, null)
        return if (str.isNullOrBlank()) null else Uri.parse(str)
    }

    fun saveRootUri(uri: Uri) {
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    // ── API Key ──

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    // ── 模型名（默认免费版） ──

    fun getModel(): String {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun saveModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    // ── AI 识别提示词（空/未设置 → 默认，默认值单点来源 DEFAULT_NAME_PROMPT） ──

    fun getAiPrompt(): String {
        return prefs.getString(KEY_AI_PROMPT, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAME_PROMPT
    }

    fun saveAiPrompt(prompt: String) {
        prefs.edit().putString(KEY_AI_PROMPT, prompt.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "evidkeeper_settings"
        private const val KEY_ROOT_URI = "root_uri"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_AI_PROMPT = "ai_prompt"
        private const val DEFAULT_MODEL = "glm-4.6v-flash"

        /** 默认 AI 提示词（v1.0 内置文案，逐字保留，勿改） */
        val DEFAULT_NAME_PROMPT = """
你是一个证据文件归档助手。根据文件内容，生成一个简洁的事项名称用于归类。
规则：
- 2-6 个中文字符
- 概括文件的核心内容（如录取通知书→录取通知书，租房合同→房屋租赁，工资单→工资流水）
- 禁止返回"扫描件""PDF文件""图片""文档"等只描述格式的名称
- 如果内容不足以判断，根据文件名推断最合理的分类
- 只回复名称，不要解释、标点或任何附加文字
""".trimIndent()
    }
}
