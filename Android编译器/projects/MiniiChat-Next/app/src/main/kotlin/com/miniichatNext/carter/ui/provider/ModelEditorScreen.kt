package com.miniichatNext.carter.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ThinkingLevel

/**
 * 单个模型的独立编辑页面。
 *
 * 由 [ProviderEditorScreen] 内部以全屏覆盖层的方式打开，不占用导航栈。
 *
 * 与提供商级配置的分工：
 *   - 提供商级：Base URL、API Key、协议类型、全局请求头
 *   - 模型级（本页）：模型 ID、显示名、温度、max tokens、思考等级、
 *     Response API 开关、以及仅对该模型生效的请求头与额外请求体
 *
 * 模型级配置优先于提供商级：例如模型级设置了 temperature，
 * 实际请求就用模型级的值，不受全局设置影响。
 */
@Composable
fun ModelEditorScreen(
    model: ModelConfig?,
    providerName: String,
    providerIsClaude: Boolean,
    onCancel: () -> Unit,
    onSave: (ModelConfig) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var modelId by remember { mutableStateOf(model?.modelId ?: "") }
    var displayName by remember { mutableStateOf(model?.displayName ?: "") }
    var temperatureText by remember {
        mutableStateOf(model?.temperature?.toString() ?: "")
    }
    var maxTokensText by remember {
        mutableStateOf(model?.maxTokens?.toString() ?: "")
    }
    var thinkingLevel by remember {
        mutableStateOf(model?.thinking() ?: ThinkingLevel.OFF)
    }
    var useResponseApi by remember { mutableStateOf(model?.useResponseApi ?: false) }
    var headerRows by remember {
        mutableStateOf<List<Pair<String, String>>>(
            model?.customHeaders?.map { it.key to it.value } ?: emptyList()
        )
    }
    var bodyRows by remember {
        mutableStateOf<List<Pair<String, String>>>(
            model?.extraBody?.map { it.key to it.value } ?: emptyList()
        )
    }
    var advancedOpen by remember {
        mutableStateOf(
            (model?.customHeaders?.isNotEmpty() == true) ||
                (model?.extraBody?.isNotEmpty() == true)
        )
    }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var showResponseApiWarning by remember { mutableStateOf(false) }

    val isNew = model == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        // ---------- 顶部栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isNew) "添加模型" else "编辑模型",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    providerName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isNew && onDelete != null) {
                IconButton(onClick = { deleteConfirmOpen = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除模型",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(
                enabled = modelId.isNotBlank() || displayName.isNotBlank(),
                onClick = {
                    val effectiveId = modelId.trim().ifBlank { displayName.trim() }
                    val temp = temperatureText.trim().toFloatOrNull()
                    val maxTok = maxTokensText.trim().toIntOrNull()
                    val headers = headerRows
                        .filter { it.first.isNotBlank() }
                        .associate { it.first.trim() to it.second.trim() }
                    val body = bodyRows
                        .filter { it.first.isNotBlank() }
                        .associate { it.first.trim() to it.second.trim() }

                    onSave(
                        ModelConfig(
                            modelId = effectiveId,
                            displayName = displayName.trim().ifBlank { effectiveId },
                            customHeaders = headers,
                            extraBody = body,
                            temperature = temp,
                            maxTokens = maxTok,
                            thinkingLevel = thinkingLevel.name,
                            useResponseApi = useResponseApi
                        )
                    )
                }
            ) { Text("保存") }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )

        // ---------- 表单 ----------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            ModelField(
                label = "模型 ID",
                hint = "调用接口时实际发送的 model 字段。" +
                    "例如 gpt-4o、claude-sonnet-4-5、deepseek-chat"
            ) {
                ModelValueBox(
                    value = modelId,
                    placeholder = "必填",
                    monospace = true
                ) { modelId = it }
            }

            ModelField(
                label = "显示名称",
                hint = "仅用于界面展示，留空则与模型 ID 相同"
            ) {
                ModelValueBox(
                    value = displayName,
                    placeholder = modelId.ifBlank { "可留空" },
                    monospace = false
                ) { displayName = it }
            }

            ModelField(
                label = "温度（可选）",
                hint = "留空则使用全局设置中的温度。" +
                    "数值越低输出越稳定，建议代码类任务用 0 ~ 0.3"
            ) {
                ModelValueBox(
                    value = temperatureText,
                    placeholder = "留空使用全局值",
                    monospace = true
                ) {
                    // 只允许数字、小数点与负号，避免写入无法解析的字符
                    temperatureText = it.filter { c ->
                        c.isDigit() || c == '.' || c == '-'
                    }
                }
            }

            ModelField(
                label = "最大输出 Token（可选）",
                hint = "留空则使用提供商级配置。" +
                    "Claude 系模型必须设置，否则接口会报 max_tokens 缺失"
            ) {
                ModelValueBox(
                    value = maxTokensText,
                    placeholder = "例如 8192",
                    monospace = true
                ) {
                    maxTokensText = it.filter { c -> c.isDigit() }
                }
            }

            if (providerIsClaude) {
                ModelField(
                    label = "思考等级",
                    hint = "仅 Claude 系模型支持。开启后会额外占用 max tokens 预算，" +
                        "并禁用 temperature 参数"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(3.dp)
                    ) {
                        ThinkingLevel.entries.forEach { lvl ->
                            val selected = thinkingLevel == lvl
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surface
                                        else Color.Transparent
                                    )
                                    .clickable { thinkingLevel = lvl }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (lvl) {
                                        ThinkingLevel.OFF -> "关"
                                        ThinkingLevel.LOW -> "低"
                                        ThinkingLevel.MEDIUM -> "中"
                                        ThinkingLevel.HIGH -> "高"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (thinkingLevel != ThinkingLevel.OFF) {
                        Text(
                            "思考预算 ${thinkingLevel.budgetTokens} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }
            }

            if (!providerIsClaude) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "使用 Response API",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "部分渠道（如 xAI）只提供 /responses 而非 /chat/completions。" +
                                "开启后本模型会改走该路径。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useResponseApi,
                        onCheckedChange = { v ->
                            if (v && !useResponseApi) {
                                showResponseApiWarning = true
                            } else {
                                useResponseApi = v
                            }
                        }
                    )
                }
            }

            // ---------- 高级选项 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { advancedOpen = !advancedOpen }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模型级高级选项",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (advancedOpen) Icons.Default.ExpandLess
                    else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (advancedOpen) {
                ModelKeyValueEditor(
                    title = "模型专属请求头",
                    hint = "会与提供商级请求头合并，同名时以本处为准",
                    rows = headerRows,
                    onChange = { headerRows = it }
                )
                ModelKeyValueEditor(
                    title = "模型专属额外请求体",
                    hint = "直接注入到请求 JSON 顶层，用于传递渠道特有参数。" +
                        "值会自动识别为布尔 / 数字 / 字符串",
                    rows = bodyRows,
                    onChange = { bodyRows = it }
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    // ---------- 删除确认 ----------
    if (deleteConfirmOpen && onDelete != null) {
        val label = model?.displayName?.ifBlank { model.modelId } ?: modelId
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text("删除模型") },
            text = {
                Text(
                    "确认从该提供商中移除模型 \"$label\" 吗？\n" +
                        "该模型的自定义参数会一并丢失。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmOpen = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---------- Response API 风险提示 ----------
    if (showResponseApiWarning) {
        AlertDialog(
            onDismissRequest = { showResponseApiWarning = false },
            title = { Text("启用 Response API") },
            text = {
                Text(
                    "该模式要求渠道实现 /responses 接口。\n" +
                        "若提供商并不支持，调用会直接返回 404 或格式错误。\n\n" +
                        "建议先在上一页执行一次连通性测试，确认接口可用后再启用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    useResponseApi = true
                    showResponseApiWarning = false
                }) { Text("仍要启用") }
            },
            dismissButton = {
                TextButton(onClick = { showResponseApiWarning = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ---------------------------------------------------------------
// 局部组件
// ---------------------------------------------------------------

@Composable
private fun ModelField(
    label: String,
    hint: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ModelValueBox(
    value: String,
    placeholder: String,
    monospace: Boolean,
    onChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                )
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true
        )
    }
}

@Composable
private fun ModelKeyValueEditor(
    title: String,
    hint: String,
    rows: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        rows.forEachIndexed { index, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Key 输入
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (k.isEmpty()) {
                        Text(
                            "键",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                        )
                    }
                    BasicTextField(
                        value = k,
                        onValueChange = { newK ->
                            val next = rows.toMutableList()
                            next[index] = newK to v
                            onChange(next)
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Value 输入
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (v.isEmpty()) {
                        Text(
                            "值",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                        )
                    }
                    BasicTextField(
                        value = v,
                        onValueChange = { newV ->
                            val next = rows.toMutableList()
                            next[index] = k to newV
                            onChange(next)
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                IconButton(
                    onClick = {
                        val next = rows.toMutableList()
                        next.removeAt(index)
                        onChange(next)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .clickable { onChange(rows + ("" to "")) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "添加一行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
