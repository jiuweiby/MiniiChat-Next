package com.miniichatNext.carter.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import kotlin.math.roundToInt

/**
 * 上下文压缩配置对话框。
 *
 * 关于「保留最近消息数」的交互调整：
 *   旧实现用一个自由输入的 OutlinedTextField，用户可以输入 0 或 999。
 *   输入 0 会把用户最后的提问一起压掉，输入 999 会直接命中「无需压缩」分支，
 *   两种情况下点击确定都像是「没反应」。现在改为受限滑块，
 *   上限由当前会话消息数动态算出（总数的三分之一），从交互上就杜绝非法值。
 */
@Composable
fun CompressContextDialog(
    totalMessages: Int = 0,
    /** 允许保留的最大条数，由 ChatViewModel.compressAvailability() 计算 */
    maxKeep: Int = 3,
    /** 历史中的成功工具调用次数，用于提示用户哪些信息会被保留 */
    toolCallCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) -> Unit
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var targetTokens by remember { mutableStateOf(2000) }

    // 滑块实际值范围 [1, maxKeep]，用 Float 承载以适配 Slider
    val upperBound = maxKeep.coerceAtLeast(1)
    var keepRecent by remember(upperBound) {
        mutableFloatStateOf(upperBound.coerceAtMost(3).toFloat())
    }
    val keepRecentInt = keepRecent.roundToInt().coerceIn(1, upperBound)

    val tokenOptions = listOf(500, 1000, 2000, 4000)
    val compressibleCount = (totalMessages - keepRecentInt).coerceAtLeast(0)
    val canConfirm = compressibleCount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.context_compress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---------- 概况 ----------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatRow("当前会话总消息数", "$totalMessages 条", primary = true)
                        StatRow("历史工具调用成功", "$toolCallCount 次")
                        StatRow("本次将压缩", "$compressibleCount 条", primary = true)
                        StatRow("保留原文", "$keepRecentInt 条")
                    }
                }

                // ---------- 压缩说明 ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "压缩后会生成一份结构化技术档案，" +
                            "包含任务目标、已完成事项、关键文件路径、代码要点、" +
                            "已确认结论、待办问题与约束偏好。" +
                            "文件路径、类名、方法名与代码片段都会被保留。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ---------- 保留条数（滑块，天然限制范围） ----------
                Text(
                    "保留最近原文条数（1 - $upperBound）",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = keepRecent,
                        onValueChange = { keepRecent = it },
                        valueRange = 1f..upperBound.toFloat(),
                        steps = (upperBound - 2).coerceAtLeast(0),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$keepRecentInt",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ---------- 目标 token（仅作参考展示，不影响摘要结构） ----------
                Text(
                    stringResource(R.string.compress_target_tokens),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tokenOptions.forEach { tokens ->
                        val selected = tokens == targetTokens
                        TextButton(onClick = { targetTokens = tokens }) {
                            Text(
                                "$tokens",
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // ---------- 额外要求 ----------
                OutlinedTextField(
                    value = additionalPrompt,
                    onValueChange = { additionalPrompt = it },
                    label = { Text(stringResource(R.string.compress_additional_prompt)) },
                    placeholder = { Text("例如：重点保留所有涉及的网络请求参数与返回结构") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                if (!canConfirm) {
                    Text(
                        text = "当前消息数不足，无法压缩。请先进行更多对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(targetTokens, keepRecentInt, additionalPrompt)
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** 概况区的单行键值展示 */
@Composable
private fun StatRow(
    label: String,
    value: String,
    primary: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (primary) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
