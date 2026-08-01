package com.example.evidkeeper.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.example.evidkeeper.util.FileType
import com.example.evidkeeper.util.classifyFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 智谱 GLM API 客户端。使用 org.json 构建请求体避免转义问题。
 */
object GlmApiClient {

    private const val TAG = "GlmApiClient"
    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    private const val CHAT_URL = "$BASE_URL/chat/completions"
    private const val FILES_URL = "$BASE_URL/files"
    private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024L
    private const val MAX_PDF_SIZE = 50 * 1024 * 1024L
    private const val MAX_TEXT_LEN = 30000
    private const val MAX_RETRIES = 3

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── 提示词 ──

    private val NAME_PROMPT = """
你是一个证据文件归档助手。根据文件内容，生成一个简洁的事项名称用于归类。
规则：
- 2-6 个中文字符
- 概括文件的核心内容（如录取通知书→录取通知书，租房合同→房屋租赁，工资单→工资流水）
- 禁止返回"扫描件""PDF文件""图片""文档"等只描述格式的名称
- 如果内容不足以判断，根据文件名推断最合理的分类
- 只回复名称，不要解释、标点或任何附加文字
""".trimIndent()

    // ═══════════════════════════════════════════════════════════

    class GenerateError(message: String, val isFatal: Boolean = false) : Exception(message)

    suspend fun generateName(
        context: Context,
        fileUri: Uri,
        apiKey: String,
        model: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (fileName, mimeType) = getFileInfo(context, fileUri)
            val fileType = classifyFile(fileName, mimeType)
            Log.d(TAG, "generateName: $fileName type=$fileType model=$model")

            val name = when (fileType) {
                FileType.IMAGE -> generateFromImage(context, fileUri, apiKey, model)
                FileType.PDF -> generateFromPdf(context, fileUri, apiKey, model)
                FileType.TXT -> generateFromTxt(context, fileUri, apiKey, model)
                FileType.OTHER -> throw GenerateError("暂不支持该文件类型，支持：图片、PDF、TXT")
            }
            Result.success(cleanName(name))
        } catch (e: GenerateError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "generateName failed", e)
            Result.failure(GenerateError("生成失败：${e.message ?: "未知错误"}"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 图片
    // ═══════════════════════════════════════════════════════════

    private suspend fun generateFromImage(
        context: Context, uri: Uri, apiKey: String, model: String,
    ): String {
        var bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw GenerateError("无法读取图片文件")

        Log.d(TAG, "image size: ${bytes.size / 1024}KB")
        if (bytes.size > MAX_IMAGE_SIZE) {
            bytes = compressImage(bytes)
            Log.d(TAG, "compressed: ${bytes.size / 1024}KB")
        }

        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: "jpg"
        val mime = when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUrl = "data:$mime;base64,$b64"

        val body = chatBody(model, listOf(
            systemMsg(NAME_PROMPT),
            userMsgVision("请为这张图片的内容生成事项名称。", dataUrl),
        ))

        return callChatApi(apiKey, body)
    }

    // ═══════════════════════════════════════════════════════════
    // PDF
    // ═══════════════════════════════════════════════════════════

    private suspend fun generateFromPdf(
        context: Context, uri: Uri, apiKey: String, model: String,
    ): String {
        val (fileName, mimeType) = getFileInfo(context, uri)
        val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw GenerateError("无法读取文件")
        if (fileBytes.size > MAX_PDF_SIZE) throw GenerateError("文件过大（>50MB），无法处理")

        val fileId = uploadFileForExtract(fileName, mimeType, fileBytes, apiKey)
        val content = fetchExtractedContent(fileId, apiKey)
        Log.d(TAG, "PDF extracted: ${content.length} chars")

        // 提取文本太短（图片型 PDF/扫描件）→ 渲染首页为图片走 vision
        if (content.length < 50) {
            Log.d(TAG, "PDF text too short, fallback to vision")
            return generateFromPdfViaVision(context, uri, apiKey, model)
        }

        val text = if (content.length > MAX_TEXT_LEN) content.take(MAX_TEXT_LEN) else content

        val body = chatBody(model, listOf(
            systemMsg(NAME_PROMPT),
            userMsg("文件内容：\n$text"),
        ))

        return callChatApi(apiKey, body)
    }

    /** PDF 文本提取不足时：用 PdfRenderer 渲染首页为图片，走 vision */
    private suspend fun generateFromPdfViaVision(
        context: Context, uri: Uri, apiKey: String, model: String,
    ): String {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw GenerateError("无法打开 PDF")
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) throw GenerateError("PDF 无页面")

            // 渲染第一页
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(
                page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888  // 2x 分辨率
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // bitmap → jpeg bytes
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            val imgBytes = bos.toByteArray()
            bitmap.recycle()
            Log.d(TAG, "PDF page rendered: ${imgBytes.size / 1024}KB")

            val b64 = Base64.encodeToString(imgBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$b64"

            val body = chatBody(model, listOf(
                systemMsg(NAME_PROMPT),
                userMsgVision("请为这份 PDF 文件的内容生成事项名称。", dataUrl),
            ))

            return callChatApi(apiKey, body)
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TXT
    // ═══════════════════════════════════════════════════════════

    private suspend fun generateFromTxt(
        context: Context, uri: Uri, apiKey: String, model: String,
    ): String {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw GenerateError("无法读取文本文件")
        if (text.isBlank()) throw GenerateError("文件内容为空")

        val truncated = if (text.length > MAX_TEXT_LEN) text.take(MAX_TEXT_LEN) else text

        val body = chatBody(model, listOf(
            systemMsg(NAME_PROMPT),
            userMsg("文件内容：\n$truncated"),
        ))

        return callChatApi(apiKey, body)
    }

    // ═══════════════════════════════════════════════════════════
    // 通用 API 调用
    // ═══════════════════════════════════════════════════════════

    private suspend fun callChatApi(apiKey: String, body: JSONObject): String {
        val bodyStr = body.toString()
        Log.d(TAG, "chat request: ${bodyStr.take(300)}...")

        return withRetry("名称生成") {
            val request = Request.Builder()
                .url(CHAT_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(request).execute()
            val respText = resp.body?.string() ?: ""
            Log.d(TAG, "chat response: code=${resp.code} body=${respText.take(300)}")

            if (!resp.isSuccessful) {
                val msg = parseError(respText)
                val fatal = resp.code in listOf(401, 403)
                throw GenerateError("AI 请求失败（${resp.code}）：$msg", isFatal = fatal)
            }

            val content = JSONObject(respText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            Log.d(TAG, "AI result: [$content]")
            content
        }
    }

    private suspend fun uploadFileForExtract(
        fileName: String, mimeType: String, fileBytes: ByteArray, apiKey: String,
    ): String = withRetry("文件上传") {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "file-extract")
            .addFormDataPart("file", fileName, fileBytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(FILES_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val resp = client.newCall(request).execute()
        val respText = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            throw GenerateError("上传失败（${resp.code}）：${parseError(respText)}")
        }
        JSONObject(respText).getString("id")
    }

    private suspend fun fetchExtractedContent(fileId: String, apiKey: String): String =
        withRetry("内容提取") {
            delay(2000) // 等服务端处理
            val request = Request.Builder()
                .url("$FILES_URL/$fileId/content")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val resp = client.newCall(request).execute()
            val respText = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw GenerateError("内容提取失败（${resp.code}）")
            JSONObject(respText).optString("content", "")
        }

    // ═══════════════════════════════════════════════════════════
    // 请求体构造（org.json）
    // ═══════════════════════════════════════════════════════════

    private fun chatBody(model: String, messages: List<JSONObject>): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("messages", JSONArray(messages))
            put("temperature", 0.3)
            put("max_tokens", 4000)
        }
    }

    private fun systemMsg(content: String): JSONObject {
        return JSONObject().apply {
            put("role", "system")
            put("content", content)
        }
    }

    private fun userMsg(content: String): JSONObject {
        return JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
    }

    private fun userMsgVision(text: String, imageUrl: String): JSONObject {
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray(listOf(
                JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                },
                JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", imageUrl)
                    })
                },
            )))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════

    private suspend fun <T> withRetry(tag: String, block: suspend () -> T): T {
        var lastErr: Exception? = null
        for (i in 0..MAX_RETRIES) {
            try {
                return block()
            } catch (e: GenerateError) {
                if (e.isFatal) throw e
                lastErr = e
                if (i < MAX_RETRIES) delay(((i + 1) * 2000).toLong())
            } catch (e: Exception) {
                lastErr = e
                if (i < MAX_RETRIES) delay(((i + 1) * 2000).toLong())
            }
        }
        throw lastErr ?: GenerateError("未知错误")
    }

    private fun getFileInfo(context: Context, uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment ?: "unknown"
        var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (n >= 0) name = cursor.getString(n)
            }
        }
        return name to mime
    }

    private fun cleanName(raw: String): String {
        return raw.trim()
            .replace(Regex("""^["'「」]|["'「」]$"""), "")
            .lines().firstOrNull()?.trim() ?: raw.trim()
    }

    private fun parseError(body: String?): String {
        if (body.isNullOrBlank()) return "无响应"
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(100)
        } catch (_: Exception) {
            body.take(100)
        }
    }

    private fun compressImage(bytes: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            val ratio = bytes.size.toFloat() / MAX_IMAGE_SIZE
            inSampleSize = when { ratio > 8 -> 4; ratio > 2 -> 2; else -> 1 }
            inJustDecodeBounds = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        var quality = 80
        var out: ByteArray
        do {
            ByteArrayOutputStream().use { bos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                out = bos.toByteArray()
            }
            quality -= 10
        } while (out.size > MAX_IMAGE_SIZE && quality >= 20)
        bitmap.recycle()
        return out
    }
}
