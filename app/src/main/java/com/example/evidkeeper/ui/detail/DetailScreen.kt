package com.example.evidkeeper.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.evidkeeper.data.model.Item
import com.example.evidkeeper.ui.components.YearMonthPickerDialog
import com.example.evidkeeper.util.FileType
import com.example.evidkeeper.util.classifyFile

// 颜色常量
private val StarYellow = Color(0xFFFFD700)
private val StarGrey = Color(0xFF9E9E9E)
private val illegalChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
) {
    val item by viewModel.item.collectAsState()
    val context = LocalContext.current
    val toastMsg by viewModel.toastMessage.collectAsState()
    val isAdding by viewModel.isAdding.collectAsState()

    // Toast 提示
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // ── 对话框状态 ──
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showRenameFileDialog by remember { mutableStateOf<String?>(null) } // 旧文件名
    var showDeleteFileDialog by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    // 长按文件菜单
    var fileMenuExpanded by remember { mutableStateOf(false) }
    var fileMenuTarget by remember { mutableStateOf<String?>(null) }

    // 选择文件（支持多选）
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) viewModel.addFiles(uris.toList())
    }

    Scaffold(
        topBar = {
            item?.let { itm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 返回箭头
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }

                    // 事项名称（点击改名）
                    TextButton(onClick = {
                        renameText = itm.meta.name
                        renameError = null
                        showRenameDialog = true
                    }) {
                        Text(
                            itm.meta.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 星标（点击切换）
                    IconButton(onClick = { viewModel.toggleImportant() }) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = if (itm.meta.important) "取消重要" else "设为重要",
                            tint = if (itm.meta.important) StarYellow else StarGrey,
                        )
                    }

                    // 时间（点击改年月）
                    TextButton(onClick = { showTimePicker = true }) {
                        Text(
                            "${itm.meta.year}-${"%02d".format(itm.meta.month)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        bottomBar = {
            // 底部居中 +
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                FilledTonalButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    enabled = !isAdding,
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("添加中…")
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "添加文件")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加文件")
                    }
                }
            }
        },
    ) { padding ->
        item?.let { itm ->
            if (itm.files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无文件，点击下方 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(itm.files, key = { it.name ?: it.uri.hashCode() }) { file ->
                        FileGridItem(
                            file = file,
                            onClick = { openFile(context, file) },
                            onLongClick = {
                                fileMenuTarget = file.name
                                fileMenuExpanded = true
                            },
                        )

                        // 长按菜单
                        Box {
                            DropdownMenu(
                                expanded = fileMenuExpanded && fileMenuTarget == file.name,
                                onDismissRequest = {
                                    fileMenuExpanded = false
                                    fileMenuTarget = null
                                },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("改名") },
                                    onClick = {
                                        fileMenuExpanded = false
                                        fileMenuTarget = null
                                        // 只显示主名，不显示扩展名
                                        val fullName = file.name ?: ""
                                        val dot = fullName.lastIndexOf('.')
                                        renameText = if (dot > 0) fullName.substring(0, dot) else fullName
                                        renameError = null
                                        showRenameFileDialog = fullName
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        fileMenuExpanded = false
                                        fileMenuTarget = null
                                        showDeleteFileDialog = file.name
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 改名（事项）对话框 ──
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; renameError = null },
            title = { Text("修改事项名称") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = renameError?.let { err -> { Text(err) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText.trim()
                    if (t.isEmpty()) { renameError = "名称不能为空"; return@TextButton }
                    if (t.any { it in illegalChars }) { renameError = "名称不能包含这些字符：\\ / : * ? \" < > |"; return@TextButton }
                    if (viewModel.renameItem(t)) { showRenameDialog = false; renameError = null }
                    else { renameError = "文件夹名冲突，请换一个名称" }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false; renameError = null }) { Text("取消") } },
        )
    }

    // ── 时间选择对话框 ──
    if (showTimePicker && item != null) {
        YearMonthPickerDialog(
            initialYear = item!!.meta.year,
            initialMonth = item!!.meta.month,
            onConfirm = { y, m -> viewModel.changeTime(y, m); showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }

    // ── 文件改名对话框 ──
    showRenameFileDialog?.let { oldName ->
        AlertDialog(
            onDismissRequest = { showRenameFileDialog = null; renameError = null },
            title = { Text("修改文件名") },
            text = {
                Column {
                    Text(
                        "扩展名将自动保留：${oldName.substringAfterLast('.', "")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("主文件名") },
                        singleLine = true,
                        isError = renameError != null,
                        supportingText = renameError?.let { err -> { Text(err) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText.trim()
                    if (t.isEmpty()) { renameError = "文件名不能为空"; return@TextButton }
                    if (t.any { it in illegalChars }) { renameError = "名称不能包含这些字符：\\ / : * ? \" < > |"; return@TextButton }
                    if (viewModel.renameFile(oldName, t)) { showRenameFileDialog = null; renameError = null }
                    else { renameError = "改名失败，请检查文件名是否冲突" }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameFileDialog = null; renameError = null }) { Text("取消") } },
        )
    }

    // ── 文件删除对话框 ──
    showDeleteFileDialog?.let { fileName ->
        AlertDialog(
            onDismissRequest = { showDeleteFileDialog = null },
            title = { Text("删除文件") },
            text = { Text("确定删除文件「$fileName」？文件将移入回收站") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(fileName)
                    showDeleteFileDialog = null
                }) { Text("确定删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteFileDialog = null }) { Text("取消") } },
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 文件网格项
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    file: DocumentFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fileName = file.name ?: "未知"
    val fileType = classifyFile(fileName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 缩略图 / 图标
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (fileType) {
                FileType.IMAGE -> {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(file.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                FileType.PDF -> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFFD32F2F).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier.size(32.dp),
                                )
                                Text("PDF", fontSize = 10.sp, color = Color(0xFFD32F2F))
                            }
                        }
                    }
                }
                FileType.TXT -> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF1976D2).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2),
                                    modifier = Modifier.size(32.dp),
                                )
                                Text("TXT", fontSize = 10.sp, color = Color(0xFF1976D2))
                            }
                        }
                    }
                }
                FileType.OTHER -> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 文件名（单行省略）
        Text(
            text = fileName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 系统查看器打开文件
// ═══════════════════════════════════════════════════════════

private fun openFile(context: android.content.Context, file: DocumentFile) {
    val mimeType = file.type ?: context.contentResolver.getType(file.uri)
        ?: "application/octet-stream"
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
    }
}
