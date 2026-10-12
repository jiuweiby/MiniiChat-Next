package com.miniichatNext.carter.debug.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.debug.DebugLogSource
import com.miniichatNext.carter.ui.chat.copyToClipboard
import com.miniichatNext.carter.ui.chat.formatCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志行在原始行数组中的位置 + 内容 */
private class LogLine(val index: Int, val text: String)

/**
 * 日志预览页。
 *
 * ## 为什么需要这个页面
 *
 * 原来的 Debug 页只有「导出 zip / 发邮件」两个出口，
 * 排查问题时必须先把日志导出到下载目录、再用文件管理器打开，
 * 手机上这个流程极其笨重。更麻烦的是「清空日志」以前没有二次确认，
 * 误触之后历史记录直接没了。
 *
 * 本页面提供三个能力：
 *   1. 直接在应用内按行预览合并后的日志（磁盘文件 + 内存会话缓冲）
 *   2. 一键复制当前内容到剪贴板，方便贴到聊天窗口里反馈
 *   3. 带二次确认的清空操作
 *
 * ## 渲染策略
 *
 * 日志动辄几千行，不能一次性展开成多个 Text：
 *   - 用 LazyColumn 按行渲染，只有可见行参与测量
 *   - 超过 [DebugLog.previewMaxLines] 时只渲染末尾部分并给出提示
 *   - 筛选框只做子串匹配，在内存里对已切好的行数组操作，不重新读盘
 *
 * ## 布局约束
 *
 * 整个页面只有一个滚动容器（底部的 LazyColumn），
 * 顶栏、统计条、筛选框都在它之外，因此不存在嵌套滚动的问题。
 * 绝不能在这些区域再套 verticalScroll，否则会给 LazyColumn 传无限高度约束并崩溃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var rawText by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(DebugLogSource.MERGED) }
    var truncated by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    // 按原始文本切行，只在文本变化时切一次
    val allLines = remember(rawText) { splitLogLines(rawText) }

    // 筛选结果：保留原始下标，作为 LazyColumn 的稳定 key
    val visibleLines = remember(allLines, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            allLines.mapIndexed { i, s -> LogLine(i, s) }
        } else {
            allLines.mapIndexedNotNull { i, s ->
                if (s.contains(q, ignoreCase = true)) LogLine(i, s) else null
            }
        }
    }

    val listState = rememberLazyListState()

    // 读取日志：走 IO 线程，避免大文件读盘卡住主线程
    LaunchedEffect(reloadToken) {
        loading = true
        val preview = withContext(Dispatchers.IO) { DebugLog.preview(context) }
        rawText = preview.text
        source = preview.source
        truncated = preview.truncated
        loading = false
        // 默认滚到底部：排查问题时最关心最新日志
        if (preview.text.isNotEmpty()) {
            runCatching { listState.scrollToItem(Int.MAX_VALUE) }
        }
    }

    // 筛选词变化时回到顶部，方便从第一条匹配开始看
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            runCatching { listState.scrollToItem(0) }
        }
    }

    val maxLines = remember { DebugLog.previewMaxLines() }
    val rendered = remember(visibleLines) {
        if (visibleLines.size > maxLines) visibleLines.takeLast(maxLines) else visibleLines
    }
    val linesClipped = visibleLines.size > maxLines

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.debug_preview_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = sourceLabel(source),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadToken++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.debug_refresh)
                        )
                    }
                    IconButton(
                        enabled = rawText.isNotBlank(),
                        onClick = {
                            copyToClipboard(context, rawText)
                            Toast.makeText(
                                context,
                                context.getString(R.string.debug_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                            tint = if (rawText.isNotBlank()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.debug_clear),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LogStatsBar(
                source = source,
                charCount = rawText.length,
                totalLines = allLines.size,
                matchedLines = visibleLines.size,
                filtered = query.isNotBlank(),
                truncated = truncated || linesClipped
            )

            LogFilterField(
                query = query,
                onQueryChange = { query = it }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingBlock()

                    rawText.isBlank() -> EmptyBlock()

                    rendered.isEmpty() -> NoMatchBlock(query)

                    else -> LogLineList(
                        listState = listState,
                        lines = rendered,
                        query = query
                    )
                }
            }
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
                            // 用同步版本：确保紧接着重新读取时看到的是已清空的状态
                            withContext(Dispatchers.IO) { DebugLog.clearSyncNow(context) }
                            Toast.makeText(
                                context,
                                context.getString(R.string.debug_clear_done),
                                Toast.LENGTH_SHORT
                            ).show()
                            reloadToken++
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

/** 顶部统计条：来源、字符数、行数、截断提示 */
@Composable
private fun LogStatsBar(
    source: DebugLogSource,
    charCount: Int,
    totalLines: Int,
    matchedLines: Int,
    filtered: Boolean,
    truncated: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (filtered) {
                stringResource(R.string.debug_filtered_stats, matchedLines, totalLines)
            } else {
                stringResource(
                    R.string.debug_preview_stats,
                    formatCount(charCount),
                    totalLines
                )
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (truncated) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.debug_preview_truncated, formatCount(charCount)),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        if (source == DebugLogSource.BUFFER) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "当前等级以上的日志尚未落盘，以下内容全部来自内存缓冲",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/** 筛选输入框 */
@Composable
private fun LogFilterField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = {
            Text(
                text = stringResource(R.string.debug_search_hint),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.debug_search_clear),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

/**
 * 日志行列表。
 *
 * key 用 [LogLine.index]（该行在**未筛选**数组里的下标）而不是当前列表位置：
 * 筛选变化时同一个位置对应的行内容完全不同，
 * 若用位置当 key，Compose 会把旧行的测量结果复用到新行上，
 * 表现为滚动位置错乱、内容闪烁。
 */
@Composable
private fun LogLineList(
    listState: LazyListState,
    lines: List<LogLine>,
    query: String
) {
    val highlight = remember(query) { query.trim().lowercase() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(
            count = lines.size,
            key = { index -> lines[index].index },
            contentType = { "log_line" }
        ) { index ->
            LogLineRow(
                text = lines[index].text,
                highlight = highlight
            )
        }
    }
}

/**
 * 单条日志行。
 *
 * 命中筛选词时整行套一层高亮底色。
 * 这里刻意不对文本做 AnnotatedString 局部染色：
 * 日志行通常很长，逐段构造 span 会产生大量临时对象，
 * 而整行高亮在视觉上已经足够区分。
 */
@Composable
private fun LogLineRow(text: String, highlight: String) {
    val hit = highlight.isNotEmpty() && text.lowercase().contains(highlight)

    Text(
        text = text.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hit) {
                    Modifier.background(
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = 1.dp)
    )
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.debug_preview_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyBlock() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.debug_preview_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoMatchBlock(query: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "没有匹配「$query」的日志行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 把日志文本切成行数组。
 *
 * 直接用 split 而不是逐字符扫描：
 * Kotlin 的 split 对 \n 做了针对性优化，几 MB 文本的分割耗时可忽略，
 * 手写扫描反而更容易在 \r\n 上出错。
 *
 * 每行末尾统一去掉 \r，兼容从 Windows 环境产生的日志文件。
 */
private fun splitLogLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    return if (parts.size == 1 && parts[0].isEmpty()) {
        emptyList()
    } else {
        parts.map { it.removeSuffix("\r") }
    }
}

/** 日志来源的展示文案 */
@Composable
private fun sourceLabel(source: DebugLogSource): String = when (source) {
    DebugLogSource.FILE -> stringResource(
        R.string.debug_preview_source_file,
        DebugLog.level.name
    )
    DebugLogSource.BUFFER -> stringResource(R.string.debug_preview_source_buffer)
    DebugLogSource.MERGED -> stringResource(R.string.debug_preview_source_merged)
}
