package com.miniichatNext.carter.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.data.backup.BackupManager
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember { BackupManager(context) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showConfirmRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreBytes by remember { mutableStateOf<ByteArray?>(null) }

    // 自定义密钥弹窗状态
    var showCustomKeyDialog by remember { mutableStateOf(false) }
    var customKeyDraft by remember { mutableStateOf("") }
    var isExportAction by remember { mutableStateOf(true) }

    // 默认保存路径
    val defaultBackupDir = "/storage/emulated/0"

    // SAF 文件选择器（用于导入 .mnbk 备份）
    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isProcessing = true
                statusMessage = "正在读取备份文件..."
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        pendingRestoreBytes = bytes
                        showConfirmRestoreDialog = true
                    } else {
                        Toast.makeText(context, "无法读取文件或文件为空", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                    statusMessage = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全量数据备份与还原", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 说明横幅
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "安全混淆与全量保护",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "备份涵盖所有会话、模型提供商配置、智能体助手、MCP 配置以及通用设置，采用动态 XOR 混淆加解密。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // 导出全量数据
            SectionHeader("导出全量备份")
            SectionCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("导出至本地存储", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "默认保存在 $defaultBackupDir 目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    statusMessage = "正在导出全量数据..."
                                    try {
                                        val path = backupManager.exportFullBackup(customKey = null)
                                        if (path != null) {
                                            Toast.makeText(context, "备份已成功保存到:\n$path", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "导出异常: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isProcessing = false
                                        statusMessage = null
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("快速导出")
                        }

                        Button(
                            onClick = {
                                isExportAction = true
                                customKeyDraft = ""
                                showCustomKeyDialog = true
                            },
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("加密导出...")
                        }
                    }
                }
            }

            // 还原全量数据
            SectionHeader("导入与恢复备份")
            SectionCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("选择备份文件恢复", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "支持 .mnbk 备份包导入，恢复将与现有数据合并",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                importFilePicker.launch("*/*")
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("选取备份文件")
                        }
                    }
                }
            }

            if (isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = statusMessage ?: "正在处理...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 确认还原弹窗
    if (showConfirmRestoreDialog && pendingRestoreBytes != null) {
        val bytes = pendingRestoreBytes!!
        AlertDialog(
            onDismissRequest = {
                showConfirmRestoreDialog = false
                pendingRestoreBytes = null
            },
            icon = { Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("确认恢复备份数据？") },
            text = {
                Text("即将恢复该备份中的所有聊天、模型提供商与配置数据。如果备份设置了自定义加密密钥，请点击“自定义密钥还原”。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmRestoreDialog = false
                        scope.launch {
                            isProcessing = true
                            statusMessage = "正在恢复全量数据..."
                            val ok = backupManager.restoreFullBackup(bytes, customKey = null)
                            isProcessing = false
                            statusMessage = null
                            pendingRestoreBytes = null
                            if (ok) {
                                Toast.makeText(context, "全量数据已成功恢复！", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "恢复失败，可能密钥不匹配或数据已损坏", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("默认恢复")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmRestoreDialog = false
                        isExportAction = false
                        customKeyDraft = ""
                        showCustomKeyDialog = true
                    }
                ) {
                    Text("输入加密密钥恢复")
                }
            }
        )
    }

    // 自定义密钥弹窗
    if (showCustomKeyDialog) {
        AlertDialog(
            onDismissRequest = { showCustomKeyDialog = false },
            icon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(if (isExportAction) "自定义加密导出" else "输入解密密钥") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入自定义密码/密钥：")
                    OutlinedTextField(
                        value = customKeyDraft,
                        onValueChange = { customKeyDraft = it },
                        placeholder = { Text("密码不可留空") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCustomKeyDialog = false
                        val key = customKeyDraft.trim()
                        if (key.isBlank()) {
                            Toast.makeText(context, "密钥不能为空", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isExportAction) {
                            scope.launch {
                                isProcessing = true
                                statusMessage = "正在加密导出..."
                                val path = backupManager.exportFullBackup(customKey = key)
                                isProcessing = false
                                statusMessage = null
                                if (path != null) {
                                    Toast.makeText(context, "加密备份已保存到:\n$path", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            val bytes = pendingRestoreBytes
                            if (bytes != null) {
                                scope.launch {
                                    isProcessing = true
                                    statusMessage = "正在解密恢复..."
                                    val ok = backupManager.restoreFullBackup(bytes, customKey = key)
                                    isProcessing = false
                                    statusMessage = null
                                    pendingRestoreBytes = null
                                    if (ok) {
                                        Toast.makeText(context, "数据成功恢复！", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "密钥错误或数据解密失败", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    enabled = customKeyDraft.isNotBlank()
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomKeyDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
