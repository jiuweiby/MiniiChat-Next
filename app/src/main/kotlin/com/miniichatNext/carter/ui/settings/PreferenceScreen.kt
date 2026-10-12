package com.miniichatNext.carter.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.cache.AiResponseCacheManager
import com.miniichatNext.carter.data.cache.CacheStats
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.service.KeepAliveService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferenceScreen(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheManager = remember { AiResponseCacheManager(ctx) }
    val cacheStats by cacheManager.statsFlow.collectAsState(initial = CacheStats())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 0. 会话启动行为
            Text("会话管理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrefSwitchItem(
                        icon = Icons.Default.AddComment,
                        title = "启动应用自动开启新会话",
                        desc = "开启后每次打开软件均创建全新会话；关闭后则自动回到上次未完成的对话",
                        checked = settings.autoNewChatOnLaunch,
                        onCheckedChange = { onUpdateSettings(settings.copy(autoNewChatOnLaunch = it)) }
                    )
                }
            }

            // 1. 字体大小调节与实时预览
            Text("显示与字号", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("聊天消息文字大小: ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.bodyLarge)
                    }
                    Slider(
                        value = settings.fontSizeSp,
                        onValueChange = { onUpdateSettings(settings.copy(fontSizeSp = it)) },
                        valueRange = 12f..22f,
                        steps = 9
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "字号实时预览：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "这是一行预览文本：MiniiChat Next 会按此字号渲染聊天内容，当前字号 ${settings.fontSizeSp.toInt()} sp。",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = settings.fontSizeSp.sp,
                                lineHeight = (settings.fontSizeSp * 1.38f).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 2. 聊天卡片显示控制
            Text("卡片显示设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrefSwitchItem(
                        icon = Icons.Default.Psychology,
                        title = "显示深度思考卡片",
                        desc = "展示模型的思维链推理过程",
                        checked = settings.showReasoningCard,
                        onCheckedChange = { onUpdateSettings(settings.copy(showReasoningCard = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    PrefSwitchItem(
                        icon = Icons.Default.Visibility,
                        title = "显示工具调用卡片",
                        desc = "关闭后自动完全隐藏工具调用过程及返回内容，保持界面极简干净",
                        checked = settings.showToolInvocationCard,
                        onCheckedChange = { onUpdateSettings(settings.copy(showToolInvocationCard = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    PrefSwitchItem(
                        icon = Icons.Default.Speed,
                        title = "显示单个卡片 Token 与耗时速度",
                        desc = "在每条卡片底部展示 ↑输入 ↓输出 速度 及耗时统计",
                        checked = settings.showCardTokenUsage,
                        onCheckedChange = { onUpdateSettings(settings.copy(showCardTokenUsage = it)) }
                    )
                }
            }

            // 3. AI 缓存与 Token 节省统计
            Text("AI 响应缓存与 Token 统计", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cached, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("本地缓存挡位", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "基于精准上下文与提示词哈希命中，重复提问秒级瞬发并大幅节省 Token 消耗",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    val modes = listOf("关闭", "小", "中", "大")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        modes.forEach { m ->
                            val selected = settings.aiCacheMode == m
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        onUpdateSettings(settings.copy(aiCacheMode = m))
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = m,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))

                    // 统计面板
                    Text("Token 节省与缓存命中详情：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val rateStr = "%.1f".format(cacheStats.hitRatePercent)
                        Text("• 缓存命中率：$rateStr% (${cacheStats.hitCount}/${cacheStats.totalRequests} 次请求)", style = MaterialTheme.typography.bodyMedium)
                        Text("• 累计节省 Prompt Tokens：${cacheStats.savedPromptTokens}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• 累计节省 Completion Tokens：${cacheStats.savedCompletionTokens}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• 总节省消耗 Tokens：${cacheStats.totalSavedTokens}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            scope.launch {
                                cacheManager.clearAll()
                                Toast.makeText(ctx, "缓存及统计数据已清除", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清除缓存与统计")
                        }
                    }
                }
            }

            // 4. 输入与键盘交互
            Text("交互习惯", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrefSwitchItem(
                        icon = Icons.Default.KeyboardReturn,
                        title = "按 Enter 发送消息",
                        desc = "开启后按手机键盘回车直接发送，关闭则为换行",
                        checked = settings.sendOnEnter,
                        onCheckedChange = { onUpdateSettings(settings.copy(sendOnEnter = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    PrefSwitchItem(
                        icon = Icons.Default.ContentPaste,
                        title = "超长粘贴自动转为附件",
                        desc = "当从剪切板粘贴大段长文本时自动转为文本附件，防止输入框卡顿",
                        checked = settings.pasteAsAttachment,
                        onCheckedChange = { onUpdateSettings(settings.copy(pasteAsAttachment = it)) }
                    )
                }
            }

            // 5. 代码块排版
            Text("代码排版与高亮", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrefSwitchItem(
                        icon = Icons.Default.WrapText,
                        title = "代码框自动换行",
                        desc = "防止超长代码横向滚动超出边界",
                        checked = settings.codeWrap,
                        onCheckedChange = { onUpdateSettings(settings.copy(codeWrap = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    PrefSwitchItem(
                        icon = Icons.Default.Numbers,
                        title = "代码框显示行号",
                        desc = "在代码区域左侧显示代码行序号",
                        checked = settings.codeShowLineNumbers,
                        onCheckedChange = { onUpdateSettings(settings.copy(codeShowLineNumbers = it)) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    PrefSwitchItem(
                        icon = Icons.Default.Code,
                        title = "代码框显示复制按钮",
                        desc = "一键完整复制该代码块内容",
                        checked = settings.codeShowCopyBtn,
                        onCheckedChange = { onUpdateSettings(settings.copy(codeShowCopyBtn = it)) }
                    )
                }
            }

            // 6. 强效后台保活
            Text("系统与保活", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrefSwitchItem(
                        icon = Icons.Default.Power,
                        title = "强效后台保活",
                        desc = "启动持续常驻前台服务、透明透传悬浮窗与无声音频流。退至后台持续接收 AI 完整长回复",
                        checked = settings.keepAliveServiceEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                                    Toast.makeText(ctx, "保活需开启悬浮窗权限，请在系统设置中允许", Toast.LENGTH_LONG).show()
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${ctx.packageName}")
                                    )
                                    ctx.startActivity(intent)
                                }
                                KeepAliveService.start(ctx)
                            } else {
                                KeepAliveService.stop(ctx)
                            }
                            onUpdateSettings(settings.copy(keepAliveServiceEnabled = enabled))
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PrefSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
