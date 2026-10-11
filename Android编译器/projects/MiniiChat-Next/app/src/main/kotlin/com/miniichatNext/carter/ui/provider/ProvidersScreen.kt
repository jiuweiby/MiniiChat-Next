package com.miniichatNext.carter.ui.provider

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ModelConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.ui.components.OutlinedFieldBox
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SettingsTopBar

/** 模型列表折叠时最多展示的条数 */
private const val MODELS_COLLAPSED_LIMIT = 5

/**
 * 提供商列表页。
 *
 * 每个提供商卡片就地展示：
 *   - 提供商基础信息（名称、Base URL）
 *   - 拉取远程模型列表（带搜索与逐个添加）
 *   - 手动添加模型
 *   - 已配置模型列表，点击整行设为当前模型，点击箭头进入该模型的独立编辑页
 *   - 编辑 / 删除提供商
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvidersScreen(
    providers: List<ProviderConfig>,
    fetchingId: String?,
    discoveredModels: Map<String, List<String>> = emptyMap(),
    activeProviderId: String,
    activeModel: String,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onFetchModels: (String) -> Unit,
    onAddManualModel: (String, String) -> Unit,
    onRemoveModel: (String, String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    /** 点击某个模型进入独立编辑页 */
    onEditModel: (String, ModelConfig) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.providers), onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onCreate() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.add_provider),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (providers.isEmpty()) {
                SectionCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.no_providers),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.no_providers_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                providers.forEach { p ->
                    ProviderCard(
                        provider = p,
                        fetching = fetchingId == p.id,
                        discovered = discoveredModels[p.id] ?: emptyList(),
                        activeModel = if (activeProviderId == p.id) activeModel else "",
                        onEdit = { onEdit(p) },
                        onDelete = { onDelete(p.id) },
                        onFetch = { onFetchModels(p.id) },
                        onAddManual = { m -> onAddManualModel(p.id, m) },
                        onRemoveModel = { m -> onRemoveModel(p.id, m) },
                        onSelectModel = { m -> onSelectModel(p.id, m) },
                        onEditModel = { m -> onEditModel(p.id, m) }
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    fetching: Boolean,
    discovered: List<String>,
    activeModel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFetch: () -> Unit,
    onAddManual: (String) -> Unit,
    onRemoveModel: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onEditModel: (ModelConfig) -> Unit
) {
    var manualInput by remember { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }
    var modelsExpanded by remember(provider.id) { mutableStateOf(false) }
    var discoveredSearch by remember { mutableStateOf("") }
    var showDiscoveredSection by remember(discovered) {
        mutableStateOf(discovered.isNotEmpty())
    }

    SectionCard {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---------- 头部 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        provider.baseUrl,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { deleteOpen = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 拉取模型 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !fetching, onClick = onFetch)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (fetching) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (fetching) stringResource(R.string.fetching)
                            else stringResource(R.string.fetch_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "已配置 ${provider.modelIds().size} 个模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- 远程发现列表 ----------
            if (discovered.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDiscoveredSection = !showDiscoveredSection },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "拉取到的模型列表 (${discovered.size} 个)",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (showDiscoveredSection)
                                    Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = showDiscoveredSection) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                OutlinedFieldBox {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        BasicTextField(
                                            value = discoveredSearch,
                                            onValueChange = { discoveredSearch = it },
                                            modifier = Modifier.weight(1f),
                                            textStyle = LocalTextStyle.current.copy(
                                                color = LocalContentColor.current,
                                                fontSize = 13.sp
                                            ),
                                            cursorBrush = SolidColor(
                                                MaterialTheme.colorScheme.primary
                                            ),
                                            singleLine = true,
                                            decorationBox = { inner ->
                                                if (discoveredSearch.isEmpty()) {
                                                    Text(
                                                        "搜索模型…",
                                                        color = MaterialTheme.colorScheme
                                                            .onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                        if (discoveredSearch.isNotEmpty()) {
                                            IconButton(
                                                onClick = { discoveredSearch = "" },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                val filtered = discovered.filter {
                                    discoveredSearch.isBlank() ||
                                        it.contains(discoveredSearch, ignoreCase = true)
                                }
                                val existingSet = remember(provider.models) {
                                    provider.modelIds().toSet()
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (filtered.isEmpty()) {
                                        Text(
                                            text = "无匹配模型",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    } else {
                                        filtered.forEach { modelName ->
                                            val isAdded = modelName in existingSet
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isAdded) {
                                                            MaterialTheme.colorScheme
                                                                .surfaceVariant.copy(alpha = 0.3f)
                                                        } else {
                                                            MaterialTheme.colorScheme.surface
                                                        }
                                                    )
                                                    .padding(
                                                        horizontal = 10.dp,
                                                        vertical = 6.dp
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = modelName,
                                                    style = MaterialTheme.typography
                                                        .bodySmall.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 12.sp
                                                        ),
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isAdded) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "已添加",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                } else {
                                                    IconButton(
                                                        onClick = { onAddManual(modelName) },
                                                        modifier = Modifier.size(26.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Add,
                                                            contentDescription = "添加至列表",
                                                            tint = MaterialTheme.colorScheme
                                                                .primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 手动添加模型 ----------
            OutlinedFieldBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(
                            color = LocalContentColor.current,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (manualInput.isEmpty()) {
                                Text(
                                    stringResource(R.string.add_model_manually),
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
                            .clip(CircleShape)
                            .background(
                                if (canAdd) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            )
                            .clickable(enabled = canAdd) {
                                onAddManual(manualInput.trim())
                                manualInput = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "add",
                            modifier = Modifier.size(16.dp),
                            tint = if (canAdd) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // ---------- 已配置模型 ----------
            if (provider.models.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "点击整行设为当前模型，点击箭头编辑该模型专属参数",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                val total = provider.models.size
                val showAll = modelsExpanded || total <= MODELS_COLLAPSED_LIMIT
                val visible = if (showAll) {
                    provider.models
                } else {
                    provider.models.take(MODELS_COLLAPSED_LIMIT)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.forEach { model ->
                        val id = model.modelId.ifBlank { model.displayName }
                        ModelEntryRow(
                            model = model,
                            selected = id == activeModel,
                            onSelect = { onSelectModel(id) },
                            onEdit = { onEditModel(model) },
                            onRemove = { onRemoveModel(id) }
                        )
                    }
                }

                if (total > MODELS_COLLAPSED_LIMIT) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { modelsExpanded = !modelsExpanded }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (modelsExpanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (modelsExpanded) {
                                    stringResource(R.string.collapse_models)
                                } else {
                                    stringResource(
                                        R.string.expand_models,
                                        total - MODELS_COLLAPSED_LIMIT
                                    )
                                },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            confirmButton = {
                TextButton(onClick = { onDelete(); deleteOpen = false }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.delete) + " · " + provider.name) },
            text = { Text(stringResource(R.string.provider_delete_confirm, provider.name)) }
        )
    }
}

/**
 * 已配置模型的单行条目。
 *
 * 整行点击 = 设为当前模型
 * 右侧箭头 = 进入独立编辑页
 * 尾部叉号 = 移除该模型
 */
@Composable
private fun ModelEntryRow(
    model: ModelConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val id = model.modelId.ifBlank { model.displayName }
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = id,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 已自定义参数的提示，让用户一眼看出哪些模型被单独调过
            val marks = buildList {
                model.temperature?.let { add("温度$it") }
                model.maxTokens?.let { add("max$it") }
                if (model.thinking() != ThinkingLevel.OFF) {
                    add("思考${model.thinking().name.lowercase()}")
                }
                if (model.useResponseApi) add("RespAPI")
                if (model.customHeaders.isNotEmpty()) add("头${model.customHeaders.size}")
                if (model.extraBody.isNotEmpty()) add("体${model.extraBody.size}")
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

        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "编辑模型",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除模型",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
