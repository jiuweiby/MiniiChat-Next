package com.miniichatNext.carter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.Assistant
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.ui.components.AssistantAvatarBadge
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDrawer(
    conversations: List<Conversation>,
    activeId: String?,
    assistants: List<com.miniichatNext.carter.data.model.Assistant>,
    activeAssistantId: String,
    onSelectAssistant: (String) -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var query by remember { mutableStateOf("") }
    var showAssistantPicker by remember { mutableStateOf(false) }

    // 置顶会话 ID 集合
    val pinnedIds = remember { mutableStateListOf<String>() }

    // 批量删除模式管理
    var isBatchDeleteMode by remember { mutableStateOf(false) }
    val selectedDeleteIds = remember { mutableStateListOf<String>() }
    var showConfirmBatchDelete by remember { mutableStateOf(false) }

    val currentAssistant = assistants.firstOrNull { it.id == activeAssistantId }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    // 置顶排序：置顶的会话排在最前面
    val sorted = remember(filtered, pinnedIds.toList()) {
        filtered.sortedWith(
            compareByDescending<Conversation> { it.id in pinnedIds }
                .thenByDescending { it.updatedAt }
        )
    }

    val grouped = remember(sorted, pinnedIds.toList()) { groupConversationsByDate(sorted, pinnedIds) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().width(312.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
        ) {
            // 标头或批量选择工具栏
            if (isBatchDeleteMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        isBatchDeleteMode = false
                        selectedDeleteIds.clear()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择")
                    }
                    Text(
                        text = "已选 ${selectedDeleteIds.size} 项",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = selectedDeleteIds.isNotEmpty(),
                        onClick = { showConfirmBatchDelete = true }
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "删除选中",
                            tint = if (selectedDeleteIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.new_chat))
                    }
                }
            }

            // 搜索框
            if (!isBatchDeleteMode) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text(
                                        stringResource(R.string.search_chats),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (query.isBlank()) stringResource(R.string.no_chats_yet)
                            else stringResource(R.string.no_matches),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    grouped.forEach { (label, items) ->
                        item(key = "h-$label") {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(items, key = { it.id }) { conv ->
                            val isPinned = conv.id in pinnedIds
                            val isChecked = conv.id in selectedDeleteIds
                            ChatRow(
                                conv = conv,
                                selected = conv.id == activeId,
                                isPinned = isPinned,
                                isBatchMode = isBatchDeleteMode,
                                isChecked = isChecked,
                                onToggleCheck = {
                                    if (isChecked) selectedDeleteIds.remove(conv.id)
                                    else selectedDeleteIds.add(conv.id)
                                },
                                onSwipeRight = {
                                    // 从左向右划：若未开启批量删除，则立即开启并将划动的会话加入选中
                                    if (!isBatchDeleteMode) {
                                        isBatchDeleteMode = true
                                        selectedDeleteIds.clear()
                                        selectedDeleteIds.add(conv.id)
                                    } else {
                                        if (!isChecked) selectedDeleteIds.add(conv.id)
                                    }
                                },
                                onClick = {
                                    if (isBatchDeleteMode) {
                                        if (isChecked) selectedDeleteIds.remove(conv.id)
                                        else selectedDeleteIds.add(conv.id)
                                    } else {
                                        onSelect(conv.id)
                                    }
                                },
                                onTogglePin = {
                                    if (isPinned) pinnedIds.remove(conv.id)
                                    else pinnedIds.add(conv.id)
                                },
                                onRename = { renameTarget = conv },
                                onDelete = { deleteTarget = conv }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showAssistantPicker = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistantAvatarBadge(
                        avatar = currentAssistant?.avatar,
                        avatarPath = currentAssistant?.avatarPath,
                        fallbackName = currentAssistant?.name,
                        size = 28.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        currentAssistant?.name ?: stringResource(R.string.assistant),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 设置
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.padding(WindowInsets.navigationBars.asPaddingValues()))
        }
    }

    renameTarget?.let { conv ->
        var newTitle by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    onRename(conv.id, newTitle.ifBlank { "" })
                    renameTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, singleLine = true)
            }
        )
    }

    deleteTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = { onDelete(conv.id); deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text("确认删除会话 \"${conv.title}\" 吗？此操作不可撤销。") }
        )
    }

    // 确认批量删除弹窗
    if (showConfirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmBatchDelete = false },
            title = { Text("批量删除会话") },
            text = { Text("确认永久删除已选中的 ${selectedDeleteIds.size} 个会话吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedDeleteIds.forEach { id -> onDelete(id) }
                        selectedDeleteIds.clear()
                        isBatchDeleteMode = false
                        showConfirmBatchDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmBatchDelete = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAssistantPicker) {
        ModalBottomSheet(onDismissRequest = { showAssistantPicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.assistants), style = MaterialTheme.typography.titleLarge)
                assistants.forEach { assistant ->
                    val selected = assistant.id == activeAssistantId
                    Card(
                        onClick = {
                            onSelectAssistant(assistant.id)
                            showAssistantPicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        ListItem(
                            headlineContent = { Text(assistant.name) },
                            leadingContent = {
                                if (assistant.hasAvatarImage) {
                                    AssistantAvatarBadge(
                                        avatar = assistant.avatar,
                                        avatarPath = assistant.avatarPath,
                                        fallbackName = assistant.name,
                                        size = 40.dp
                                    )
                                } else {
                                    Text(
                                        assistant.avatar.ifBlank { assistant.name.take(1) },
                                        fontSize = 22.sp
                                    )
                                }
                            },
                            trailingContent = {
                                if (selected) Icon(Icons.Default.Check, contentDescription = null)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    conv: Conversation,
    selected: Boolean,
    isPinned: Boolean,
    isBatchMode: Boolean,
    isChecked: Boolean,
    onToggleCheck: () -> Unit,
    onSwipeRight: () -> Unit,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = when {
        isChecked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.surfaceVariant
        isPinned -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    var totalDrag by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 60f) {
                            onSwipeRight()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f }
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleCheck() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
        } else if (isPinned) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = "已置顶",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }

        Text(
            conv.title.ifBlank { stringResource(R.string.untitled) },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected || isPinned) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!isBatchMode) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert, contentDescription = "more",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "取消置顶" else "置顶会话") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { menuOpen = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

private fun groupConversationsByDate(
    list: List<Conversation>,
    pinnedIds: List<String>
): List<Pair<String, List<Conversation>>> {
    if (list.isEmpty()) return emptyList()
    val pinned = mutableListOf<Conversation>()
    val remaining = mutableListOf<Conversation>()
    list.forEach {
        if (it.id in pinnedIds) pinned.add(it) else remaining.add(it)
    }

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val yStart = todayStart - 86_400_000L
    val sStart = todayStart - 7 * 86_400_000L
    val tStart = todayStart - 30 * 86_400_000L
    val today = mutableListOf<Conversation>(); val yest = mutableListOf<Conversation>()
    val sevenDays = mutableListOf<Conversation>(); val thirtyDays = mutableListOf<Conversation>()
    val older = mutableListOf<Conversation>()
    remaining.sortedByDescending { it.updatedAt }.forEach { c ->
        when {
            c.updatedAt >= todayStart -> today += c
            c.updatedAt >= yStart -> yest += c
            c.updatedAt >= sStart -> sevenDays += c
            c.updatedAt >= tStart -> thirtyDays += c
            else -> older += c
        }
    }
    val out = mutableListOf<Pair<String, List<Conversation>>>()
    if (pinned.isNotEmpty()) out += "置顶会话" to pinned
    if (today.isNotEmpty()) out += "今天" to today
    if (yest.isNotEmpty()) out += "昨天" to yest
    if (sevenDays.isNotEmpty()) out += "前7天" to sevenDays
    if (thirtyDays.isNotEmpty()) out += "前30天" to thirtyDays
    if (older.isNotEmpty()) out += "更早" to older
    return out
}
