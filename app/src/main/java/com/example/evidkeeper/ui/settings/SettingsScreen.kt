package com.example.evidkeeper.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

// AI 提示词最大长度（防止误粘贴超长文本）
private const val MAX_PROMPT_LEN = 2000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val rootUri by viewModel.rootUri.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val aiPrompt by viewModel.aiPrompt.collectAsState()
    val context = LocalContext.current

    var apiKeyVisible by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    val modelOptions = listOf("glm-4.6v-flash" to "免费版 glm-4.6v-flash", "glm-4.6v" to "正式版 glm-4.6v")

    // SAF 目录选择
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setRootUri(uri)
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
                Text("设置", style = MaterialTheme.typography.titleMedium)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 存储文件夹 ──
            Text("存储文件夹", style = MaterialTheme.typography.labelLarge)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { folderPicker.launch(null) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = rootUri?.toString() ?: "未设置（点击选择）",
                    modifier = Modifier.padding(12.dp),
                    color = if (rootUri != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Divider()

            // ── API Key ──
            Text("智谱 API Key", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.setApiKey(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (apiKeyVisible) "隐藏" else "显示",
                        )
                    }
                },
            )

            Divider()

            // ── AI 模型选择 ──
            Text("AI 模型", style = MaterialTheme.typography.labelLarge)
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
            ) {
                OutlinedTextField(
                    value = modelOptions.firstOrNull { it.first == model }?.second ?: model,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                ) {
                    modelOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setModel(value)
                                modelExpanded = false
                            },
                        )
                    }
                }
            }

            Divider()

            // ── AI 识别提示词 ──
            Text("AI 识别提示词", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = aiPrompt,
                onValueChange = { viewModel.setAiPrompt(it.take(MAX_PROMPT_LEN)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提示词") },
                minLines = 5,
                maxLines = 10,
                supportingText = { Text("${aiPrompt.length}/$MAX_PROMPT_LEN，留空时使用默认提示词") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { viewModel.resetAiPrompt() }) { Text("恢复默认") }
            }

            // ── 保存按钮 ──
            Button(
                onClick = {
                    viewModel.save()
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
