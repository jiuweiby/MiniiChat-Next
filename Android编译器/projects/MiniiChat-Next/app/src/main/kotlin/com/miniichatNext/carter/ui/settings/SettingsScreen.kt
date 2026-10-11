package com.miniichatNext.carter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.mcp.McpServerConfig
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.ui.components.AvatarView
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import com.miniichatNext.carter.ui.components.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    userProfile: UserProfile,
    providers: List<ProviderConfig>,
    mcpServers: List<McpServerConfig>,
    onOpenProviders: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPreference: () -> Unit,
    onOpenTitleModelPicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onSetSystemPrompt: (String) -> Unit,
    onSetTemperature: (Float) -> Unit,
    onSetStream: (Boolean) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetThemeMode: (String) -> Unit,
    onOpenThemeColorPicker: () -> Unit,
    onSetLanguage: (String) -> Unit,
    onBack: () -> Unit
) {
    var systemPromptText by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.settings),
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. 个人资料卡片 (点击编辑昵称与头像)
            item {
                UserProfileCard(
                    userProfile = userProfile,
                    onClick = onOpenProfile
                )
            }

            // 2. 个性设置卡片 (字号、字号预览、思考卡片、Token与速度统计、强效保活等)
            item {
                PreferenceNavigationCard(onClick = onOpenPreference)
            }

            // 3. 标题生成模型配置入口
            item {
                TitleModelNavigationCard(
                    currentTitleModel = settings.titleModel,
                    onClick = onOpenTitleModelPicker
                )
            }

            // 4. 模型服务提供商卡片
            item {
                ProviderNavigationCard(
                    providerCount = providers.size,
                    onClick = onOpenProviders
                )
            }

            // 5. MCP 工具服务器配置入口卡片
            item {
                McpNavigationCard(
                    mcpCount = mcpServers.size,
                    onClick = onOpenMcp
                )
            }

            // 6. 数据备份与还原入口卡片
            item {
                BackupNavigationCard(onClick = onOpenBackup)
            }

            // 7. 模型生成参数配置
            item {
                SectionHeader(stringResource(R.string.section_behavior))
                SectionCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = systemPromptText,
                            onValueChange = {
                                systemPromptText = it
                                onSetSystemPrompt(it)
                            },
                            label = { Text(stringResource(R.string.setting_system)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6
                        )

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_temperature),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = String.format("%.1f", settings.temperature),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.temperature,
                                onValueChange = onSetTemperature,
                                valueRange = 0f..2f,
                                steps = 19
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_stream),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Switch(
                                checked = settings.stream,
                                onCheckedChange = onSetStream
                            )
                        }
                    }
                }
            }

            // 8. 外观与系统设置
            item {
                SectionHeader(stringResource(R.string.setting_appearance))
                SectionCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_dynamic_color),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.setting_dynamic_color_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = onSetDynamicColor
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SettingItemRow(
                            icon = Icons.Default.DarkMode,
                            title = stringResource(R.string.setting_theme_mode),
                            value = when (settings.themeMode) {
                                "light" -> stringResource(R.string.setting_theme_light)
                                "dark" -> stringResource(R.string.setting_theme_dark)
                                else -> stringResource(R.string.setting_theme_system)
                            },
                            onClick = {
                                val next = when (settings.themeMode) {
                                    "system" -> "light"
                                    "light" -> "dark"
                                    else -> "system"
                                }
                                onSetThemeMode(next)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SettingItemRow(
                            icon = Icons.Default.Palette,
                            title = "主题色",
                            value = if (settings.accentColor.isEmpty()) "默认" else settings.accentColor.uppercase(),
                            onClick = onOpenThemeColorPicker
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SettingItemRow(
                            icon = Icons.Default.Language,
                            title = stringResource(R.string.setting_language),
                            value = when (settings.language) {
                                "zh" -> stringResource(R.string.setting_language_zh)
                                "en" -> stringResource(R.string.setting_language_en)
                                else -> stringResource(R.string.setting_language_system)
                            },
                            onClick = {
                                val next = when (settings.language) {
                                    "system" -> "zh"
                                    "zh" -> "en"
                                    else -> "system"
                                }
                                onSetLanguage(next)
                            }
                        )
                    }
                }
            }

            // 9. 关于软件
            item {
                SectionHeader(stringResource(R.string.section_about))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAbout),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.section_about),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun TitleModelNavigationCard(
    currentTitleModel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Title,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "会话标题生成模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (currentTitleModel.isNotBlank()) "当前模型: $currentTitleModel" else "未配置专属模型（默认跟随当前活跃模型）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun UserProfileCard(
    userProfile: UserProfile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(
                avatar = userProfile.avatar,
                fallbackInitial = userProfile.displayName.take(1).ifBlank { "U" },
                size = 56.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userProfile.displayName.ifBlank { "You" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "点击修改称呼与专属头像",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PreferenceNavigationCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "个性设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "字号调节与实时预览、深度思考/工具卡片、Token与速度统计、强效后台保活",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ProviderNavigationCard(
    providerCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.providers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "已配置 $providerCount 个提供商",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun McpNavigationCard(
    mcpCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MCP 工具服务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (mcpCount > 0) "已添加 $mcpCount 个外部 MCP 服务及内置文件工具" else "内置文件操作工具与外部 MCP 拓展",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BackupNavigationCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "数据备份与还原",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "支持全量数据自定义 XOR 加密导出与还原导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SettingItemRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
