package com.example.evidkeeper.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.sp
import com.example.evidkeeper.data.model.Item

// 星标颜色
private val StarYellow = Color(0xFFFFD700)
private val StarGrey = Color(0xFF9E9E9E)

// 非法字符
private val illegalChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenDetail: (Int) -> Unit,
    onOpenCreate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items by viewModel.items.collectAsState()

    // 回到页面时刷新列表
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 对话框状态 ──
    var showRenameDialog by remember { mutableStateOf<Item?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Item?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    // ── 搜索文本（UI 可用但不影响列表——步骤 6 才接入过滤） ──
    var searchQuery by remember { mutableStateOf("") }

    // ── 长按菜单 ──
    var menuExpanded by remember { mutableStateOf(false) }
    var menuTargetItem by remember { mutableStateOf<Item?>(null) }

    Scaffold(
        topBar = {
            // 顶部栏：搜索框 + + 按钮 + 齿轮按钮（加状态栏间距）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索事项") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onOpenCreate) {
                    Icon(Icons.Default.Add, contentDescription = "新建事项")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            // 空态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有事项，点右上角 + 新建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items, key = { it.meta.id }) { item ->
                    ItemRow(
                        item = item,
                        onLongClick = {
                            menuTargetItem = item
                            menuExpanded = true
                        },
                        onClick = { onOpenDetail(item.meta.id) },
                    )

                    // 长按弹出菜单
                    Box {
                        DropdownMenu(
                            expanded = menuExpanded && menuTargetItem == item,
                            onDismissRequest = {
                                menuExpanded = false
                                menuTargetItem = null
                            },
                        ) {
                            DropdownMenuItem(
                                text = { Text("改名") },
                                onClick = {
                                    menuExpanded = false
                                    menuTargetItem = null
                                    renameText = item.meta.name
                                    renameError = null
                                    showRenameDialog = item
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    menuTargetItem = null
                                    showDeleteDialog = item
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 改名对话框 ──
    showRenameDialog?.let { item ->
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = null
                renameError = null
            },
            title = { Text("修改事项名称") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("名称") },
                        singleLine = true,
                        isError = renameError != null,
                        supportingText = renameError?.let { err -> { Text(err) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameText.trim()
                    // 校验
                    if (trimmed.isEmpty()) {
                        renameError = "名称不能为空"
                        return@TextButton
                    }
                    if (trimmed.any { it in illegalChars }) {
                        renameError = "名称不能包含这些字符：\\ / : * ? \" < > |"
                        return@TextButton
                    }
                    val ok = viewModel.renameItem(item, trimmed)
                    if (ok) {
                        showRenameDialog = null
                        renameError = null
                    } else {
                        renameError = "文件夹名冲突，请换一个名称"
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = null
                    renameError = null
                }) { Text("取消") }
            },
        )
    }

    // ── 删除确认对话框 ──
    showDeleteDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除事项") },
            text = { Text("确定删除事项「${item.meta.name}」？所有文件将移入回收站") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(item)
                    showDeleteDialog = null
                }) { Text("确定删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            },
        )
    }

}

// ═══════════════════════════════════════════════════════════
// 列表行组件
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: Item,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 事项名称（左侧，单行省略）
        Text(
            text = item.meta.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 文件数量
        Text(
            text = "${item.files.size}个文件",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 星标（只显示，不可点击）
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = if (item.meta.important) "重要" else "普通",
            tint = if (item.meta.important) StarYellow else StarGrey,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 时间
        Text(
            text = "${item.meta.year}-${"%02d".format(item.meta.month)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
