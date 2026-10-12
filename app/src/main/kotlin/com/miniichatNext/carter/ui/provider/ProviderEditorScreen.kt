package com.miniichatNext.carter.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderPresets
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.data.provider.ProviderTester
import com.miniichatNext.carter.util.newId

/**
 * 提供商编辑器。
 *
 * 页面结构分两层，用同文件内的状态切换实现，不占用导航栈：
 *
 *   第 1 层（本函数主体）：提供商级配置
 *     名称、Base URL、API Key、协议类型、全局参数、自定义请求头、连通性测试
 *
 *   第 2 层（[ModelEditorScreen]）：模型级配置
 *     点击模型列表里的任一项进入，独立全屏页面
 *     包含模型 ID、显示名、温度、max tokens、思考等级、Response API、
 *     模型专属请求头与额外请求体
 *
 * 为什么模型编辑不走进 AppRoot 的导航栈：
 *     本页正在编辑的 ProviderConfig 可能是「尚未保存的新提供商」，
 *     它没有稳定 id，无法通过 id 从 store 里反查。
 *     如果走导航栈，子页面的编辑结果就要靠跨页通信回传，
 *     而 AnimatedContent 同一时刻只组合一个页面，这会引入不必要的复杂度。
 *     把第 2 层收在本组件内部，models 列表天然是共享状态，零通信成本。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderEditorScreen(
    initial: ProviderConfig?,
    onCancel: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    testRunning: Boolean = false,
    testRunningKind: ProviderTester.Kind? = null,
    testResults: Map<ProviderTester.Kind, ProviderTester.Result> = emptyMap(),
    onRunTest: ((ProviderConfig, String) -> Unit)? = null
) {
    // ---------- 提供商级状态 ----------
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember(initial?.id) { mutableStateOf(initial?.apiKey ?: "") }
    var chatCompletionsPath by remember(initial?.id) {
        mutableStateOf(initial?.chatCompletionsPath ?: "/chat/completions")
    }
    var useResponseApi by remember(initial?.id) { mutableStateOf(initial?.useResponseApi ?: false) }
    var presetBaseUrl by remember(initial?.id) { mutableStateOf("") }
    var providerType by remember(initial?.id) {
        mutableStateOf(initial?.type() ?: ProviderType.OPENAI)
    }
    var thinkingLevel by remember(initial?.id) {
        mutableStateOf(initial?.thinking() ?: ThinkingLevel.OFF)
    }
    var maxTokens by remember(initial?.id) {
        mutableStateOf((initial?.maxTokens ?: 8192).toString())
    }
    var headerRows by remember(initial?.id) {
        mutableStateOf<List<Pair<String, String>>>(
            initial?.customHeaders?.map { it.key to it.value } ?: emptyList()
        )
    }
    var bodyRows by remember(initial?.id) {
        mutableStateOf<List<Pair<String, String>>>(
            initial?.extraBody?.map { it.key to it.value } ?: emptyList()
        )
    }
    var advancedOpen by remember(initial?.id) {
        mutableStateOf(
            (initial?.customHeaders?.isNotEmpty() == true) ||
                (initial?.extraBody?.isNotEmpty() == true)
        )
    }
    var presetSheetOpen by remember(initial?.id) { mutableStateOf(initial == null) }
    var showResponseApiWarning by remember { mutableStateOf(false) }

    // ---------- 模型级状态 ----------
    var models by remember(initial?.id) { mutableStateOf(initial?.models ?: emptyList()) }
    var manualModelInput by remember(initial?.id) { mutableStateOf("") }
    var testModelId by remember(initial?.id) {
        mutableStateOf(
            initial?.models?.firstOrNull()?.let { it.modelId.ifBlank { it.displayName } } ?: ""
        )
    }

    // 第 2 层：非空时表示正在编辑某个模型
    // 用 Pair 而非单个 ModelConfig，是为了区分「新增」与「编辑」：
    // 前者传入 null，后者传入原模型，删除按钮仅在编辑时出现
    var editingModel by remember(initial?.id) {
        mutableStateOf<ModelEditTarget?>(null)
    }

    /** 组装当前界面上的全部字段 */
    fun buildConfig(): ProviderConfig {
        val effective = baseUrl.trim().ifEmpty {
            presetBaseUrl.ifEmpty { initial?.baseUrl ?: "" }
        }
        val headers = headerRows
            .filter { it.first.isNotBlank() }
            .associate { it.first.trim() to it.second.trim() }
        val body = bodyRows
            .filter { it.first.isNotBlank() }
            .associate { it.first.trim() to it.second.trim() }
        val base = initial ?: ProviderConfig(
            id = newId(),
            name = name.trim(),
            baseUrl = effective.trimEnd('/'),
            apiKey = apiKey.trim()
        )
        return base.copy(
            name = name.trim(),
            baseUrl = effective.trimEnd('/'),
            apiKey = apiKey.trim(),
            models = models,
            customHeaders = headers,
            extraBody = body,
            providerType = providerType.name,
            thinkingLevel = thinkingLevel.name,
            maxTokens = maxTokens.toIntOrNull() ?: 8192,
            chatCompletionsPath = chatCompletionsPath.trim().ifBlank { "/chat/completions" },
            useResponseApi = useResponseApi
        )
    }

    val canSave = name.isNotBlank() && (
        baseUrl.isNotBlank() ||
            presetBaseUrl.isNotBlank() ||
            initial?.baseUrl?.isNotBlank() == true
        )

    // ===============================================================
    // 第 2 层：模型编辑页（全屏覆盖，不占导航栈）
    // ===============================================================
    val target = editingModel
    if (target != null) {
        ModelEditorScreen(
            model = target.model,
            providerName = name.ifBlank { initial?.name ?: "新提供商" },
            providerIsClaude = providerType == ProviderType.CLAUDE,
            onCancel = { editingModel = null },
            onSave = { updated ->
                // 就地合并：按模型 id 匹配替换，匹配不到则追加
                val key = updated.modelId.ifBlank { updated.displayName }
                val next = models.toMutableList()
                val idx = next.indexOfFirst {
                    it.modelId.ifBlank { it.displayName } == key
                }
                if (idx >= 0) next[idx] = updated else next.add(updated)
                models = next
                // 同步测试目标模型
                if (testModelId.isBlank()) testModelId = key
                editingModel = null
            },
            onDelete = if (target.model != null) {
                {
                    val key = target.model.modelId.ifBlank { target.model.displayName }
                    models = models.filterNot {
                        it.modelId.ifBlank { it.displayName } == key
                    }
                    if (testModelId == key) {
                        testModelId = models.firstOrNull()
                            ?.let { it.modelId.ifBlank { it.displayName } } ?: ""
                    }
                    editingModel = null
                }
            } else null
        )
        return
    }

    // ===============================================================
    // 第 1 层：提供商配置页
    // ===============================================================
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
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel)
                )
            }
            Text(
                if (initial == null) stringResource(R.string.add_provider)
                else stringResource(R.string.edit_provider),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = canSave,
                onClick = { onSave(buildConfig()) }
            ) { Text(stringResource(R.string.action_save)) }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (initial == null) {
                TextButton(onClick = { presetSheetOpen = true }) {
                    Text(stringResource(R.string.choose_preset))
                }
            }

            // ---------- 协议类型 ----------
            FieldSection(stringResource(R.string.provider_type)) {
                SegmentedRow(
                    labels = ProviderType.entries.map {
                        when (it) {
                            ProviderType.OPENAI ->
                                stringResource(R.string.provider_type_openai)
                            ProviderType.CLAUDE ->
                                stringResource(R.string.provider_type_claude)
                        }
                    },
                    selectedIndex = ProviderType.entries.indexOf(providerType),
                    onSelect = { providerType = ProviderType.entries[it] }
                )
            }

            // ---------- Claude 专有 ----------
            if (providerType == ProviderType.CLAUDE) {
                FieldSection(stringResource(R.string.thinking_level)) {
                    SegmentedRow(
                        labels = ThinkingLevel.entries.map {
                            when (it) {
                                ThinkingLevel.OFF -> stringResource(R.string.thinking_off)
                                ThinkingLevel.LOW -> stringResource(R.string.thinking_low)
                                ThinkingLevel.MEDIUM -> stringResource(R.string.thinking_medium)
                                ThinkingLevel.HIGH -> stringResource(R.string.thinking_high)
                            }
                        },
                        selectedIndex = ThinkingLevel.entries.indexOf(thinkingLevel),
                        onSelect = { thinkingLevel = ThinkingLevel.entries[it] }
                    )
                    if (thinkingLevel != ThinkingLevel.OFF) {
                        Text(
                            stringResource(
                                R.string.thinking_budget_label,
                                thinkingLevel.budgetTokens
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }

                FieldSection(stringResource(R.string.max_tokens)) {
                    EditableValue(
                        value = maxTokens,
                        placeholder = "8192",
                        monospace = true
                    ) { maxTokens = it.filter { c -> c.isDigit() } }
                }
            }

            // ---------- 基本信息 ----------
            FieldSection(stringResource(R.string.provider_name)) {
                EditableValue(name, "", monospace = false) { name = it }
            }
            FieldSection(stringResource(R.string.setting_base_url)) {
                EditableValue(
                    value = baseUrl,
                    placeholder = presetBaseUrl.ifEmpty { initial?.baseUrl ?: "https://…" },
                    monospace = true
                ) { baseUrl = it }
            }
            FieldSection(stringResource(R.string.setting_api_key)) {
                EditableValue(apiKey, "", monospace = true) { apiKey = it }
            }

            if (providerType == ProviderType.OPENAI) {
                FieldSection(stringResource(R.string.chat_completions_path)) {
                    EditableValue(
                        value = chatCompletionsPath,
                        placeholder = "/chat/completions",
                        monospace = true
                    ) { chatCompletionsPath = it }
                }

                SwitchRow(
                    title = stringResource(R.string.response_api),
                    subtitle = stringResource(R.string.response_api_hint),
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

            // ---------- 模型列表 ----------
            ModelsSection(
                models = models,
                manualInput = manualModelInput,
                onManualInputChange = { manualModelInput = it },
                onAddModel = { raw ->
                    val id = raw.trim()
                    if (id.isNotEmpty() && models.none {
                            it.modelId.ifBlank { it.displayName } == id
                        }
                    ) {
                        models = models + ModelConfig(modelId = id, displayName = id)
                    }
                },
                onClickModel = { idx ->
                    editingModel = ModelEditTarget(model = models[idx])
                }
            )

            // ---------- 连通性测试 ----------
            if (onRunTest != null) {
                ProviderTestPanel(
                    running = testRunning,
                    runningKind = testRunningKind,
                    results = testResults,
                    onRun = {
                        val cfg = buildConfig()
                        val model = testModelId.trim().ifBlank {
                            cfg.models.firstOrNull()?.let { it.modelId.ifBlank { it.displayName } }
                                .orEmpty()
                        }
                        onRunTest(cfg, model)
                    }
                )

                if (models.isNotEmpty()) {
                    FieldSection("测试使用的模型") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            models.forEach { m ->
                                val id = m.modelId.ifBlank { m.displayName }
                                val selected = testModelId == id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(50)
                                        )
                                        .clickable { testModelId = id }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = if (selected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
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
                    stringResource(R.string.advanced_options),
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
                KeyValueEditor(
                    title = stringResource(R.string.custom_headers),
                    hint = stringResource(R.string.custom_headers_hint),
                    rows = headerRows,
                    onChange = { headerRows = it.toMutableList() }
                )
                KeyValueEditor(
                    title = stringResource(R.string.extra_body),
                    hint = stringResource(R.string.extra_body_hint),
                    rows = bodyRows,
                    onChange = { bodyRows = it.toMutableList() }
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    // ---------- 预设选择 ----------
    if (presetSheetOpen) {
        AlertDialog(
            onDismissRequest = { presetSheetOpen = false },
            confirmButton = {
                TextButton(onClick = { presetSheetOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.pick_preset_or_manual)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProviderPresets.all.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (name.isBlank()) name = preset.name
                                    presetBaseUrl = preset.baseUrl
                                    baseUrl = preset.baseUrl
                                    providerType = preset.type
                                    chatCompletionsPath = preset.path
                                    presetSheetOpen = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Column {
                                Text(
                                    if (preset.type == ProviderType.CLAUDE) {
                                        "${preset.name}  • Claude"
                                    } else {
                                        preset.name
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    preset.hint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    if (showResponseApiWarning) {
        AlertDialog(
            onDismissRequest = { showResponseApiWarning = false },
            title = { Text(stringResource(R.string.response_api)) },
            text = { Text(stringResource(R.string.response_api_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    useResponseApi = true
                    showResponseApiWarning = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResponseApiWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 模型编辑目标：用包装类而非裸 ModelConfig，便于表达「正在编辑第几个位置」 */
private data class ModelEditTarget(val model: ModelConfig?)

// ---------------------------------------------------------------
// 模型列表区块
// ---------------------------------------------------------------

@Composable
private fun ModelsSection(
    models: List<ModelConfig>,
    manualInput: String,
    onManualInputChange: (String) -> Unit,
    onAddModel: (String) -> Unit,
    onClickModel: (Int) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "模型列表",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${models.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "点击任一模型进入独立页面，配置其专属参数（温度、max tokens、思考等级等）",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // 手动添加输入框
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
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = manualInput,
                    onValueChange = onManualInputChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (manualInput.isEmpty()) {
                            Text(
                                "输入模型 ID 后点右侧加号",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                val canAdd = manualInput.trim().isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (canAdd) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                        )
                        .clickable(enabled = canAdd) {
                            onAddModel(manualInput)
                            onManualInputChange("")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加",
                        modifier = Modifier.size(16.dp),
                        tint = if (canAdd) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEachIndexed { idx, m ->
                    ModelRow(model = m, onClick = { onClickModel(idx) })
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelConfig, onClick: () -> Unit) {
    val id = model.modelId.ifBlank { model.displayName }
    val showDisplayName = model.displayName.isNotBlank() && model.displayName != id

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = id,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showDisplayName) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 已自定义参数的角标，让用户一眼看出哪些模型被单独调过
            val marks = buildList {
                model.temperature?.let { add("温度 $it") }
                model.maxTokens?.let { add("max $it") }
                if (model.thinking() != ThinkingLevel.OFF) {
                    add("思考 ${model.thinking().name.lowercase()}")
                }
                if (model.useResponseApi) add("Response API")
                if (model.customHeaders.isNotEmpty()) add("请求头 ${model.customHeaders.size}")
                if (model.extraBody.isNotEmpty()) add("额外参数 ${model.extraBody.size}")
            }
            if (marks.isNotEmpty()) {
                Text(
                    text = marks.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = "编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

// ---------------------------------------------------------------
// 通用局部组件
// ---------------------------------------------------------------

@Composable
private fun FieldSection(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 分段选择器：把若干互斥选项排成一行等宽按钮 */
@Composable
private fun SegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val selected = selectedIndex == i
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EditableValue(
    value: String,
    placeholder: String,
    monospace: Boolean = false,
    onChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            textStyle = TextStyle(
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
private fun KeyValueEditor(
    title: String,
    hint: String,
    rows: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEachIndexed { index, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            stringResource(R.string.key),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                        )
                    }
                    BasicTextField(
                        value = k,
                        onValueChange = { newK ->
                            val nextList = rows.toMutableList()
                            nextList[index] = newK to v
                            onChange(nextList)
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
                            stringResource(R.string.value),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                        )
                    }
                    BasicTextField(
                        value = v,
                        onValueChange = { newV ->
                            val nextList = rows.toMutableList()
                            nextList[index] = k to newV
                            onChange(nextList)
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
                        val nextList = rows.toMutableList()
                        nextList.removeAt(index)
                        onChange(nextList)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
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
                stringResource(R.string.add_row),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
