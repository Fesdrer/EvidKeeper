package com.example.evidkeeper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

// ── 颜色常量 ──
private val SurfaceDark = Color(0xFF1A1A1A)
private val HighlightGold = Color(0xFFFFD700)
private val TextMuted = Color(0xFF888888)

// ═══════════════════════════════════════════════════════════
// 年月选择对话框（复用 ScrollPicker）
// ═══════════════════════════════════════════════════════════

@Composable
fun YearMonthPickerDialog(
    initialYear: Int,
    initialMonth: Int,
    onConfirm: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedYear by remember { mutableStateOf(initialYear) }
    var selectedMonth by remember { mutableStateOf(initialMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "选择时间",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 年份选择器
                ScrollPicker(
                    range = 2000..2099,
                    initialSelected = initialYear,
                    onSelected = { selectedYear = it },
                    modifier = Modifier.weight(1f),
                )
                Text("年", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
                // 月份选择器
                ScrollPicker(
                    range = 1..12,
                    initialSelected = initialMonth,
                    onSelected = { selectedMonth = it },
                    modifier = Modifier.weight(1f),
                )
                Text("月", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedYear, selectedMonth) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = SurfaceDark,
    )
}

// ═══════════════════════════════════════════════════════════
// ScrollPicker —— 通用滚动选择器
// 基于《时间设置-实现指南》的方案，注意 snapshotFlow 陷阱
// ═══════════════════════════════════════════════════════════

@Composable
fun ScrollPicker(
    range: IntRange,
    initialSelected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 44.dp,
    visibleItems: Int = 5,
) {
    val baseItems = remember(range) { range.toList() }
    val baseSize = baseItems.size

    // 空 range 直接不渲染：否则 centerItemIndex % baseSize 会除零崩溃
    if (baseSize == 0) return

    // 3 份数据实现无限滑动
    val items = remember(range) {
        buildList { repeat(3) { addAll(range.toList()) } }
    }
    val totalSize = items.size
    val coroutineScope = rememberCoroutineScope()

    // 初始定位到第 2 份副本中选中项的位置（仅首次定位用，后续不随重组重算）
    val startIndex = remember(range, initialSelected) {
        baseSize + baseItems.indexOf(initialSelected).coerceAtLeast(0) - visibleItems / 2
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startIndex.coerceAtLeast(0),
    )

    // 实时检测"哪一项在正中间"
    val centerItemIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportEndOffset / 2
            layoutInfo.visibleItemsInfo.minByOrNull { info ->
                abs((info.offset + info.size / 2) - viewportCenter)
            }?.index?.coerceIn(0, totalSize - 1) ?: 0
        }
    }

    // 选中值取模归一化：只在中心项真正变化时回调一次（重复值不重复回调）
    LaunchedEffect(Unit) {
        snapshotFlow { centerItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                onSelected(baseItems[index % baseSize])
            }
    }

    // 松手吸附（必须用 snapshotFlow，不能用 LaunchedEffect(isScrollInProgress) 当 key）
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    delay(60) // 等惯性滚动完全结束
                    val layoutInfo = listState.layoutInfo
                    val viewportCenter = layoutInfo.viewportEndOffset / 2
                    val closest = layoutInfo.visibleItemsInfo.minByOrNull { info ->
                        abs((info.offset + info.size / 2) - viewportCenter)
                    } ?: return@collect
                    val diff = closest.offset + closest.size / 2f - viewportCenter
                    if (abs(diff) > 4f) {
                        val snapFirst = (closest.index - visibleItems / 2).coerceIn(0, totalSize - 1)
                        listState.animateScrollToItem(snapFirst)
                    }
                }
            }
    }

    // 视觉呈现
    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // 高亮条（居中）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(HighlightGold.copy(alpha = 0.12f))
                .border(1.dp, HighlightGold.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
        )

        // 列表
        LazyColumn(state = listState) {
            itemsIndexed(items) { index, value ->
                val isCenter = index == centerItemIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            coroutineScope.launch {
                                val targetIndex = (index - visibleItems / 2).coerceIn(0, totalSize - 1)
                                listState.animateScrollToItem(targetIndex)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (range == (1..12)) "${value}月" else "${value}年",
                        fontSize = if (isCenter) 22.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) Color.White else TextMuted,
                    )
                }
            }
        }
    }
}
