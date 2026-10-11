package com.miniichatNext.carter.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.miniichatNext.carter.data.store.AppSettings

/**
 * 纯文本兜底的 AnnotatedString 构造器。
 *
 * 注意：不能用 AnnotatedString(text) 这个单参构造 ——
 * 在 Compose 1.7+ 里它被标记为 internal（annotations 参数版本才是 public），
 * 直接调用会导致 "Cannot access constructor ... it is internal" 编译错误。
 * buildAnnotatedString {} 是始终公开的工厂函数，这也是 parseInline / highlightCode
 * 内部使用的同一条路径。
 */
private fun plain(text: String): AnnotatedString =
    buildAnnotatedString { append(text) }

/** 内联解析的安全包装：流式输出时会在半截 markdown 上反复解析，异常不该让整条消息崩掉 */
private fun safeInline(
    text: String,
    palette: InlinePalette,
    onLink: (String) -> Unit
): AnnotatedString = runCatching { parseInline(text, palette, onLink) }.getOrElse { plain(text) }

@Composable
internal fun MarkdownBlocks(
    blocks: List<Block>,
    palette: InlinePalette,
    textColor: Color,
    settings: AppSettings? = null,
    onLink: (String) -> Unit
) {
    Column {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) {
                Spacer(Modifier.height(when {
                    block is Block.CodeBlock || blocks[idx - 1] is Block.CodeBlock -> 8.dp
                    block is Block.Table || blocks[idx - 1] is Block.Table -> 10.dp
                    block is Block.ImageBlock || blocks[idx - 1] is Block.ImageBlock -> 8.dp
                    else -> 6.dp
                }))
            }
            when (block) {
                is Block.Heading -> RenderHeading(block, textColor, palette, onLink)
                is Block.Paragraph -> RenderParagraph(block, textColor, palette, onLink)
                is Block.ImageBlock -> RenderImageBlock(block)
                is Block.BulletItem -> RenderBulletItem(block, textColor, palette, onLink)
                is Block.NumberedItem -> RenderNumberedItem(block, textColor, palette, onLink)
                is Block.Quote -> RenderQuote(block, palette, onLink)
                is Block.Hr -> RenderHr(palette)
                is Block.CodeBlock -> RenderCodeBlock(block, palette, settings, onLink)
                is Block.Table -> RenderTable(block, palette, onLink)
            }
        }
    }
}

@Composable
private fun RenderHeading(
    block: Block.Heading,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
        else -> MaterialTheme.typography.titleMedium
    }
    // 内联解析结果缓存：流式输出时同一段文本会经历数十次重组，不能每次重跑正则
    val annotated = remember(block.text, palette) {
        safeInline(block.text, palette, onLink)
    }
    Text(annotated, color = textColor, style = style)
}

@Composable
private fun RenderParagraph(
    block: Block.Paragraph,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val annotated = remember(block.text, palette) {
        safeInline(block.text, palette, onLink)
    }
    Text(
        annotated,
        color = textColor,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun RenderImageBlock(block: Block.ImageBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
    ) {
        SubcomposeAsyncImage(
            model = block.url,
            contentDescription = block.alt,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "图片加载失败: ${block.alt.ifBlank { block.url }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
        if (block.alt.isNotBlank()) {
            Text(
                text = block.alt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun RenderBulletItem(
    block: Block.BulletItem,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val annotated = remember(block.text, palette) {
        safeInline(block.text, palette, onLink)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(20.dp)) {
            Text("•", color = textColor, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            annotated,
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun RenderNumberedItem(
    block: Block.NumberedItem,
    textColor: Color,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val annotated = remember(block.text, palette) {
        safeInline(block.text, palette, onLink)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(28.dp)) {
            Text(
                text = "${block.index}.",
                color = textColor,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
            )
        }
        Text(
            annotated,
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun RenderQuote(
    block: Block.Quote,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val annotated = remember(block.text, palette) {
        safeInline(block.text, palette, onLink)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(palette.quoteBar)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            annotated,
            color = palette.quoteFg,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
        )
    }
}

@Composable
private fun RenderHr(palette: InlinePalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.outline)
    )
}

@Composable
private fun RenderCodeBlock(
    block: Block.CodeBlock,
    palette: InlinePalette,
    settings: AppSettings?,
    onLink: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val showLineNum = settings?.codeShowLineNumbers ?: true
    val showCopyBtn = settings?.codeShowCopyBtn ?: true
    val wrapCode = settings?.codeWrap ?: true

    // 代码高亮结果缓存：旧实现在 Composable 体内直接调用 highlightCode()，
    // 等于每帧对整段代码重跑一次词法分析，大代码块直接把帧率压到个位数
    val highlighted = remember(block.code, block.lang) {
        runCatching { highlightCode(block.code, block.lang, HighlightPalette.Dark) }
            .getOrElse { plain(block.code) }
    }

    // 逐行切片缓存。
    //
    // 旧实现把行号拼成一整段文本、代码拼成另一整段文本，左右两列各自测量：
    // 代码一旦发生软换行（长行 wrap），右列视觉行数就多于左列行号数，
    // 行号会「提前结束 / 整体错位」——表现就是长代码只显示到三十几行后中断。
    //
    // 现在改成「按真实物理行把代码切成 N 段」，每段与一个行号严格绑定，
    // 由同一行 Row 承载，无论代码是否折行都不会再错位。
    //
    // 切片用 AnnotatedString.subSequence：它保留原高亮 span，无需逐行重跑词法分析。
    // 每行的 [start, end) 区间在这里一次性算好并缓存，重组时零扫描。
    val codeLines = remember(block.code, highlighted) {
        val text = highlighted.text
        val result = ArrayList<Pair<Int, AnnotatedString>>()
        var start = 0
        var lineNo = 1
        var i = 0
        val len = text.length
        while (i <= len) {
            if (i == len || text[i] == '\n') {
                // 行内容不含换行符本身
                val lineEnd = i
                val slice = if (start == lineEnd) {
                    // 空行也必须用 buildAnnotatedString 构造：
                    // AnnotatedString("") 单参构造在 Compose 1.7+ 是 internal，会编译失败。
                    buildAnnotatedString { }
                } else {
                    highlighted.subSequence(start, lineEnd)
                }
                result.add(lineNo to slice)
                lineNo++
                start = i + 1
            }
            i++
        }
        result
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, palette.outline.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.lang.ifBlank { "code" },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(Modifier.weight(1f))
            if (showCopyBtn) {
                IconButton(
                    onClick = { clipboard.setText(plain(block.code)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制完整代码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.outline.copy(alpha = 0.3f))
        )

        val scrollModifier = if (wrapCode) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        }

        // 行号列固定宽度：按最大行号位数预留，避免位数变化时整列左右跳动。
        // 用最大行号的十进制位数 × 单字符估算宽度（等宽字体下约 7.5sp/字符），再加内边距。
        val gutterWidth = remember(codeLines.size) {
            val digits = codeLines.size.toString().length.coerceAtLeast(2)
            // 等宽字体 12sp 下每个数字约 7.2dp，留一点余量
            (digits * 8 + 6).dp
        }

        Column(
            modifier = scrollModifier.padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            // 逐行渲染：每一行是独立的 Row，行号与该行代码被同一个 Row 约束。
            //
            // 这是修复「行号只显示到三十几行后中断」的关键：
            // 行号不再是和整段代码并列的另一整段文本，而是物理地贴在它所属的那一行上。
            // 无论代码行是否因软换行折成多行，行号都只占这一格，绝不会累积错位。
            codeLines.forEach { (lineNo, lineText) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    if (showLineNum) {
                        Text(
                            text = lineNo.toString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.End
                            ),
                            softWrap = false,
                            modifier = Modifier.width(gutterWidth)
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(palette.outline.copy(alpha = 0.25f))
                        )
                        Spacer(Modifier.width(10.dp))
                    }

                    Text(
                        text = lineText,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderTable(
    block: Block.Table,
    palette: InlinePalette,
    onLink: (String) -> Unit
) {
    val horizontalScroll = rememberScrollState()
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    val cellBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    // 内联文本预算：避免每帧重复解析所有单元格
    val headerCells: List<AnnotatedString> = remember(block.headers, palette) {
        block.headers.map { safeInline(it, palette, onLink) }
    }
    val rowCells: List<List<AnnotatedString>> = remember(block.rows, palette) {
        block.rows.map { row -> row.map { safeInline(it, palette, onLink) } }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outlineColor, RoundedCornerShape(8.dp))
            .horizontalScroll(horizontalScroll)
    ) {
        // 旧实现用 IntrinsicSize.Max 包 Column、IntrinsicSize.Min 包每一行，
        // 表格会被测量 2~3 遍，列数越多开销越明显。
        // 这里改为内容自适应宽度 + 单次测量。
        Column {
            Row(modifier = Modifier.background(headerBg)) {
                headerCells.forEachIndexed { i, cell ->
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            cell,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    if (i < headerCells.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(36.dp)
                                .background(cellBorder)
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(outlineColor))

            rowCells.forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier.background(
                        if (rowIdx % 2 == 1)
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                ) {
                    row.forEachIndexed { i, cell ->
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                cell,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (i < row.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(36.dp)
                                    .background(cellBorder)
                            )
                        }
                    }
                }
                if (rowIdx < rowCells.lastIndex) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.8.dp).background(cellBorder))
                }
            }
        }
    }
}
