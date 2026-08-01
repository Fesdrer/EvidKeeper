package com.example.evidkeeper.util

/**
 * 文件类型分类，供缩略图图标和 AI 输入构造使用
 */
enum class FileType {
    IMAGE,
    PDF,
    TXT,
    OTHER,
}

/** 图片扩展名 */
private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

/** 文本扩展名（含 markdown） */
private val txtExtensions = setOf("txt", "md", "log", "csv", "json", "xml", "html", "htm")

/** 按扩展名判断文件类型 */
fun classifyFile(fileName: String, mimeType: String? = null): FileType {
    val ext = fileName.substringAfterLast('.', "").lowercase()

    return when {
        ext in imageExtensions -> FileType.IMAGE
        ext == "pdf" -> FileType.PDF
        ext in txtExtensions -> FileType.TXT
        // MIME 类型兜底
        mimeType != null && mimeType.startsWith("image/") -> FileType.IMAGE
        mimeType != null && mimeType == "application/pdf" -> FileType.PDF
        mimeType != null && mimeType.startsWith("text/") -> FileType.TXT
        else -> FileType.OTHER
    }
}

/** 判断是否为图片 */
fun isImage(fileName: String): Boolean = classifyFile(fileName) == FileType.IMAGE

/** 判断是否为 PDF */
fun isPdf(fileName: String): Boolean = classifyFile(fileName) == FileType.PDF

/** 判断是否为文本 */
fun isTxt(fileName: String): Boolean = classifyFile(fileName) == FileType.TXT
