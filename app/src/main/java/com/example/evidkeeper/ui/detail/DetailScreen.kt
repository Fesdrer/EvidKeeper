package com.example.evidkeeper.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 步骤 4 实现——当前为占位页面 */
@Composable
fun DetailScreen(itemId: Int, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("详情页（待实现）\n事项 ID: $itemId")
    }
}
