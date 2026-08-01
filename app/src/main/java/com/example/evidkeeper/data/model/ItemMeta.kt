package com.example.evidkeeper.data.model

import kotlinx.serialization.Serializable

/**
 * 事项元数据，对应存储文件 .meta.json
 */
@Serializable
data class ItemMeta(
    val id: Int,                             // 编号
    val name: String,                        // 事项名称（不含编号前缀）
    val year: Int,                           // 时间-年
    val month: Int,                          // 时间-月（1..12）
    val important: Boolean = false,          // 是否重要（星标）
)
