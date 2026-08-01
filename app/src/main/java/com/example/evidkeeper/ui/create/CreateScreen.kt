package com.example.evidkeeper.ui.create

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.evidkeeper.ui.components.YearMonthPickerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    onBack: () -> Unit,
) {
    val name by viewModel.name.collectAsState()
    val year by viewModel.year.collectAsState()
    val month by viewModel.month.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val done by viewModel.done.collectAsState()
    val context = LocalContext.current

    var showTimePicker by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 创建成功后返回
    LaunchedEffect(done) {
        if (done) {
            Toast.makeText(context, "已创建事项", Toast.LENGTH_SHORT).show()
            viewModel.resetDone()
            onBack()
        }
    }

    // 选择文件
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 获取文件名
            var displayName = "已选文件"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = cursor.getString(idx)
                }
            }
            viewModel.setSelectedFile(uri, displayName)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("新建事项", style = MaterialTheme.typography.titleMedium)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 添加文件按钮 ──
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (selectedFileName != null) "更换文件" else "添加文件（可选）")
            }
            // 文件预览
            selectedFileName?.let { fileName ->
                Text(
                    "已选：$fileName",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── 名称输入 + AI 按钮 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.setName(it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("事项名称") },
                    singleLine = true,
                    isError = errorMsg != null,
                    supportingText = errorMsg?.let { err -> { Text(err) } },
                )
                Spacer(modifier = Modifier.width(8.dp))
                // AI 按钮（占位，始终禁用——步骤 7 接通）
                Button(
                    onClick = { /* 步骤 7 实现 */ },
                    enabled = false,
                ) {
                    Text("AI 生成")
                }
            }

            // ── 时间显示框 ──
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("时间：${year}-${"%02d".format(month)}")
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── 创建按钮 ──
            Button(
                onClick = {
                    val err = viewModel.create()
                    errorMsg = err
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("创建")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ── 时间选择对话框 ──
    if (showTimePicker) {
        YearMonthPickerDialog(
            initialYear = year,
            initialMonth = month,
            onConfirm = { y, m -> viewModel.setTime(y, m); showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }
}
