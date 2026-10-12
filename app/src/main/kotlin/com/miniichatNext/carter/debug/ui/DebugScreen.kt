package com.miniichatNext.carter.debug.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLevel
import com.miniichatNext.carter.debug.DebugLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FEEDBACK_EMAIL = "director4168@163.com"

internal fun sendFeedbackMail(context: Context, zip: File, subject: String, body: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.chooser_send_mail))
        )
    }
}

internal fun shareZip(context: Context, zip: File, title: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}

/**
 * Debug 主页面。
 *
 * 页面内维护一个子页面状态：主面板与日志预览面板互斥切换。
 * 用状态切换而不是导航栈，是为了让「日志预览」保持轻量
 * ——它本质上是 Debug 页的一个展开视图，不是独立的业务页面。
 */
@Composable
fun DebugScreen(onBack: () -> Unit) {
    var showLogPreview by remember { mutableStateOf(false) }

    if (showLogPreview) {
        LogPreviewScreen(onBack = { showLogPreview = false })
        return
    }

    DebugMainPanel(
        onBack = onBack,
        onOpenLogPreview = { showLogPreview = true }
    )
}

@Composable
private fun DebugMainPanel(
    onBack: () -> Unit,
    onOpenLogPreview: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(DebugLog.level) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(level) { DebugLog.setLevel(context, level) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                stringResource(R.string.debug_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 日志预览入口（最显眼的位置） ----------
            ActionTile(
                icon = Icons.Default.Article,
                title = stringResource(R.string.debug_preview),
                subtitle = "在应用内直接查看日志，支持筛选与复制",
                highlighted = true,
                onClick = onOpenLogPreview
            )

            Spacer(Modifier.height(4.dp))

            Text(
                stringResource(R.string.debug_level_label, level.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebugLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = level == lvl,
                        onClick = { level = lvl },
                        label = { Text(lvl.label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ---------- 导出与反馈 ----------
            ActionTile(
                icon = Icons.Default.IosShare,
                title = stringResource(R.string.debug_export_share),
                subtitle = "打包全部日志为 zip 并分享",
                onClick = {
                    scope.launch {
                        val zip = withContext(Dispatchers.IO) {
                            runCatching { DebugLog.exportZip(context) }.getOrNull()
                        }
                        if (zip != null) {
                            shareZip(
                                context,
                                zip,
                                context.getString(R.string.debug_export_title)
                            )
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.about_export_logs_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )

            ActionTile(
                icon = Icons.Default.Mail,
                title = stringResource(R.string.debug_mail_feedback),
                subtitle = "把日志作为附件发给开发者",
                onClick = {
                    scope.launch {
                        val zip = withContext(Dispatchers.IO) {
                            runCatching { DebugLog.exportZip(context) }.getOrNull()
                        }
                        if (zip != null) {
                            sendFeedbackMail(
                                context,
                                zip,
                                context.getString(R.string.debug_mail_subject),
                                context.getString(R.string.debug_mail_body)
                            )
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.about_export_logs_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )

            ActionTile(
                icon = Icons.Default.DeleteSweep,
                title = stringResource(R.string.debug_clear),
                subtitle = "删除全部日志文件与会话缓冲",
                danger = true,
                onClick = { showClearConfirm = true }
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // ---------- 崩溃测试 ----------
            Text(
                stringResource(R.string.debug_crash_test),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CrashTestButton(
                    label = stringResource(R.string.debug_crash_main),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        DebugLog.i("CrashTest", "trigger java crash on main thread")
                        throw RuntimeException("MiniiChat Next Debug crash test (main thread)")
                    }
                )
                CrashTestButton(
                    label = stringResource(R.string.debug_crash_bg),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        DebugLog.i("CrashTest", "trigger java crash on background thread")
                        Thread {
                            Thread.sleep(200)
                            throw RuntimeException(
                                "MiniiChat Next Debug crash test (background thread)"
                            )
                        }.start()
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.debug_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.debug_clear_confirm_title)) },
            text = { Text(stringResource(R.string.debug_clear_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { DebugLog.clearSyncNow(context) }
                            Toast.makeText(
                                context,
                                context.getString(R.string.debug_cleared),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.debug_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 一行式操作入口卡片。
 *
 * 统一承载图标、标题、副标题与点击行为，
 * 避免每个操作各写一套 Row 导致间距与圆角不一致。
 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    danger: Boolean = false
) {
    val container = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        danger -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 2
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 崩溃测试按钮：统一红色描边样式 */
@Composable
private fun CrashTestButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text(label, fontSize = 12.sp)
    }
}
