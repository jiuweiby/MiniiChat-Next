package com.miniichatNext.carter.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.provider.ProviderTester

/**
 * 连通性测试面板。
 *
 * 展示三项独立测试的状态、耗时与详细输出 / 错误。
 * 三项全部通过才给出「全部通过」的结论，任意一项失败都会把错误原文摊开显示，
 * 不做「测试失败」这类无信息量的概述。
 */
@Composable
internal fun ProviderTestPanel(
    running: Boolean,
    runningKind: ProviderTester.Kind?,
    results: Map<ProviderTester.Kind, ProviderTester.Result>,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // ---------- 头部 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "连通性测试",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "分别验证流式、非流式与工具调用三项能力",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    enabled = !running,
                    onClick = onRun,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("测试中", fontSize = 13.sp)
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("开始测试", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 三项结果 ----------
            val allKinds = ProviderTester.Kind.entries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allKinds.forEach { kind ->
                    TestRow(
                        kind = kind,
                        running = running && runningKind == kind,
                        result = results[kind]
                    )
                }
            }

            // ---------- 汇总结论 ----------
            if (!running && results.size == allKinds.size) {
                Spacer(Modifier.height(12.dp))
                val passed = results.values.count { it.ok }
                val total = allKinds.size
                val summaryColor = when {
                    passed == total -> Color(0xFF2E7D32)
                    passed == 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = summaryColor.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = when {
                            passed == total ->
                                "三项全部通过（$passed/$total）。" +
                                    "该提供商与模型可正常用于对话与工具调用。"
                            passed == 0 ->
                                "三项全部失败（0/$total）。" +
                                    "请优先检查 API Key、Base URL 与网络连通性。"
                            else ->
                                "部分通过（$passed/$total）。" +
                                    "可直接使用通过的项；失败项请参考其下方的错误说明逐条排查。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** 单项测试行 */
@Composable
private fun TestRow(
    kind: ProviderTester.Kind,
    running: Boolean,
    result: ProviderTester.Result?
) {
    val statusColor = when {
        running -> MaterialTheme.colorScheme.primary
        result == null -> MaterialTheme.colorScheme.outline
        result.ok -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(
                1.dp,
                statusColor.copy(alpha = if (result != null || running) 0.45f else 0.2f),
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    ) {
        // ---------- 标题行 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    running -> CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    result == null -> Icon(
                        Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    result.ok -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(17.dp)
                    )
                    else -> Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                kind.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            val tag = when {
                running -> "进行中"
                result == null -> "未测试"
                result.ok -> "通过 · ${result.totalMs}ms"
                else -> "失败 · ${result.totalMs}ms"
            }
            Text(
                tag,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = statusColor
            )
        }

        // ---------- 成功详情 ----------
        if (result != null && result.ok && result.detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            DetailBlock(
                text = result.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 失败详情 ----------
        if (result != null && !result.ok) {
            Spacer(Modifier.height(8.dp))
            DetailBlock(
                text = result.error ?: "未知错误",
                color = MaterialTheme.colorScheme.error
            )
            if (result.rawResponse.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "原始响应片段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                DetailBlock(
                    text = result.rawResponse,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 可滚动的详情块：限制最大高度，避免超长报错把整页撑坏 */
@Composable
private fun DetailBlock(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                ),
                color = color
            )
        }
    }
}
