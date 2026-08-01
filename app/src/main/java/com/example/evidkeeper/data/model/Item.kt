package com.example.evidkeeper.data.model

import androidx.documentfile.provider.DocumentFile

/**
 * 运行时事项对象，包含 meta、文件夹引用和内部文件列表
 */
data class Item(
    val meta: ItemMeta,
    val folder: DocumentFile,
    val files: List<DocumentFile>,  // 文件夹内除 .meta.json 外的所有文件
)

/**
 * addFile 操作的结果
 */
data class AddFileResult(
    val success: Boolean,
    val newFile: DocumentFile? = null,
    val needPromptDeleteSource: Boolean = false,  // 复制成功但删源失败时提示用户
    val errorMessage: String? = null,
)
