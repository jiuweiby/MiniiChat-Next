package com.miniichatNext.carter.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 大文本安全预览组件。
 *
 * 背景：工具返回的代码 / 文件内容 / Shell 输出动辄数百 KB 乃至数 MB。
 * 旧实现把整段文本直接塞进单个 Text，并且外层套了 verticalScroll。
 * verticalScroll 会向子项传递「无限高度约束」，于是 Text 必须一次性测量整个字符串：
 *   - 等宽字体下每个字符都要独立布局，几 MB 文本的 TextLayoutResult 会占用数百 MB
 *   - 测量发生在组合阶段，异常无法被普通 try/catch 捕获，直接 OOM 崩溃
 *
 * 本组件的处理策略：
 *   1. 主界面只渲染截断后的预览片段（默认 4000 字符），保证测量量恒定
 *   2. 超出部分提供「查看全文」，跳转独立页面按行惰性渲染
 *   3. 全文页每行独立成 LazyColumn item，只有可见行参与测量与布局
 */

/** 预览阈值：超过该字符数就只显示预览片段 */
private const val PREVIEW_THRESHOLD = 4000

/** 预览片段保留的字符数（取头部，代码/日志的关键信息通常在开头） */
private const val PREVIEW_KEEP = 4000

/** 全文页单行最大字符数：防止单行超长（如压缩后的 JSON）把一行撑爆 */
private const val MAX_CHARS_PER_LINE = 2000

/** 全文页每次渲染的最大行数 */
private const val PAGE_SIZE = 20000

/**
 * 可折叠的大文本框。
 *
 * @param text 原始文本，可能非常大
 * @param onOpenFull 点击「查看全文」时回调，由调用方决定跳转方式
 * @param minHeightDp 最小高度
 * @param contentColor 文字颜色
 */
@Composable
internal fun SafeTextBox(
    text: String,
    onOpenFull: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeightDp: Int = 40,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val length = text.length
    val isTruncated = length > PREVIEW_THRESHOLD

    // 预览片段只切一次，避免每次重组都产生 substring 分配
    val preview = remember(text) {
        if (text.length > PREVIEW_KEEP) text.substring(0, PREVIEW_KEEP) else text
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(12.dp)
        ) {
            // 关键：文本框自身不套任何 scroll。
            // 外层由 ModalBottomSheet / LazyColumn 负责滚动，
            // 这里只测量固定长度的预览片段，测量量恒定，不会 OOM。
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                color = contentColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeightDp.dp)
            )
        }

        if (isTruncated) {
            TruncatedHint(
                length = length,
                onOpenFull = { onOpenFull(text) }
            )
        }
    }
}

/**
 * 截断提示条。
 *
 * 独立成组件以降低 SafeTextBox 内的括号嵌套层级 ——
 * 深层嵌套的尾随 lambda 一旦少一个括号，编译器会把错误定位到很远的地方，排查成本极高。
 */
@Composable
private fun TruncatedHint(
    length: Int,
    onOpenFull: () -> Unit
) {
    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "内容过长，仅显示前 $PREVIEW_KEEP 字符（共 ${formatCount(length)} 字符）",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenFull) {
            Icon(
                imageVector = Icons.Default.OpenInFull,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("查看全文", fontSize = 12.sp)
        }
    }
}

/**
 * 全文查看页。
 *
 * 按行切分后用 LazyColumn 渲染：
 *   - 只有可见行会被测量与布局，内存占用与文本总长度基本解耦
 *   - 超长单行按 MAX_CHARS_PER_LINE 强制切片，保证单行测量量有上界
 *   - 分页加载，避免一次构造数十万个 item 描述对象
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullTextScreen(
    title: String,
    text: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val allLines = remember(text) { splitIntoSafeLines(text) }
    var shownCount by remember(text) {
        mutableStateOf(minOf(allLines.size, PAGE_SIZE))
    }

    // 当前展示的行：取前 shownCount 条，用 subList 避免整体复制
    val visibleLines: List<Pair<Int, String>> = remember(allLines, shownCount) {
        if (shownCount >= allLines.size) allLines else allLines.subList(0, shownCount)
    }

    LaunchedEffect(text) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            FullTextTopBar(
                title = title,
                lineCount = allLines.size,
                charCount = text.length,
                onBack = onBack,
                onCopyAll = {
                    copyToClipboard(context, text)
                    Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                }
            )
        },
        content = { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                codeLineItems(visibleLines)

                if (shownCount < allLines.size) {
                    loadMoreItem(
                        remaining = allLines.size - shownCount,
                        onLoadMore = {
                            shownCount = minOf(shownCount + PAGE_SIZE, allLines.size)
                        }
                    )
                } else {
                    endSpacerItem()
                }
            }
        }
    )
}

/**
 * 把可见行注册为 LazyColumn 的懒加载 item。
 *
 * 用 LazyListScope.items(count) 而非把整块塞进一个 Column：
 * 只有真正滚到可见区域的行才会被测量与布局，这是长文本不 OOM 的根本保证。
 * 抽成独立扩展函数可以让 FullTextScreen 主体保持浅嵌套，便于排查语法问题。
 */
private fun LazyListScope.codeLineItems(lines: List<Pair<Int, String>>) {
    items(
        count = lines.size,
        key = { index -> lines[index].first },
        contentType = { "code_line" }
    ) { index ->
        val line = lines[index]
        CodeLine(lineNo = line.first, content = line.second)
    }
}

/** LazyColumn 的「载入更多」条目 */
private fun LazyListScope.loadMoreItem(
    remaining: Int,
    onLoadMore: () -> Unit
) {
    item(key = "__load_more__") {
        LoadMoreBlock(remaining = remaining, onLoadMore = onLoadMore)
    }
}

/** LazyColumn 末尾的空隙条目 */
private fun LazyListScope.endSpacerItem() {
    item(key = "__end__") {
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullTextTopBar(
    title: String,
    lineCount: Int,
    charCount: Int,
    onBack: () -> Unit,
    onCopyAll: () -> Unit
) {
    TopAppBar(
        title = {
            FullTextTitle(title = title, lineCount = lineCount, charCount = charCount)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onCopyAll) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制全文")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun FullTextTitle(
    title: String,
    lineCount: Int,
    charCount: Int
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${formatCount(charCount)} 字符 · $lineCount 行",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单行代码：等宽字体 + 行号列 */
@Composable
private fun CodeLine(lineNo: Int, content: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = lineNo.toString(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.width(52.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = content.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LoadMoreBlock(
    remaining: Int,
    onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Button(
            onClick = onLoadMore,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("载入更多（剩余 ${formatCount(remaining)} 行）")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 把文本切成 (行号, 内容) 列表，超长单行强制折行。
 *
 * 行号用于稳定 key；折行保证任意单行的字符数不超过 MAX_CHARS_PER_LINE。
 */
private fun splitIntoSafeLines(text: String): List<Pair<Int, String>> {
    if (text.isEmpty()) return listOf(1 to "")
    val out = ArrayList<Pair<Int, String>>(estimateLineCount(text))
    var lineNo = 1
    var start = 0
    val len = text.length

    while (start <= len) {
        var end = text.indexOf('\n', start)
        if (end < 0) end = len
        val segment = text.substring(start, end)

        if (segment.length > MAX_CHARS_PER_LINE) {
            var offset = 0
            while (offset < segment.length) {
                val cut = minOf(offset + MAX_CHARS_PER_LINE, segment.length)
                out.add(lineNo to segment.substring(offset, cut))
                lineNo++
                offset = cut
            }
        } else {
            out.add(lineNo to segment)
            lineNo++
        }

        if (end >= len) break
        start = end + 1
    }
    return out
}

/** 估算行数用于预分配，避免 ArrayList 反复扩容 */
private fun estimateLineCount(text: String): Int {
    val limit = minOf(text.length, 1024 * 1024)
    var count = 1
    var idx = text.indexOf('\n')
    while (idx in 0 until limit) {
        count++
        idx = text.indexOf('\n', idx + 1)
    }
    if (limit in 1 until text.length) {
        count = (count.toLong() * text.length / limit).toInt().coerceAtLeast(count)
    }
    return count.coerceIn(1, 200_000)
}

/** 把字符数量格式化为易读形式 */
internal fun formatCount(value: Int): String {
    if (value < 1000) return value.toString()
    if (value < 1_000_000) return "%.1fK".format(value / 1000.0)
    return "%.1fM".format(value / 1_000_000.0)
}

/** 复制到系统剪贴板 */
internal fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }
}

/**
 * 单行预览文本：把换行、制表符压成空格并截断。
 *
 * 旧实现用 trim().replace("\n", " ")，当参数是几 MB 的文件内容时会先复制一份等长字符串，
 * 这里改为边遍历边截断，最多只扫描 maxChars * 4 个字符，分配量有上界。
 */
internal fun singleLinePreview(raw: String, maxChars: Int = 200): String {
    if (raw.isEmpty()) return ""
    val sb = StringBuilder(minOf(raw.length, maxChars))
    val scanLimit = minOf(raw.length, maxChars * 4)
    var i = 0
    while (i < scanLimit && sb.length < maxChars) {
        val c = raw[i]
        sb.append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
        i++
    }
    return sb.toString()
}
