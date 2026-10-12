package com.miniichatNext.carter.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.ui.components.SettingsTopBar

/**
 * 主题色取色画布（方案 A：自绘）。
 *
 * 上方为 HSV 色域方块（横轴 = 色相 0..360，纵轴 = 饱和度 1..0），
 * 下方为亮度滑条。手指拖动实时取色，右上角显示预览色与 hex 值。
 */
@Composable
fun ThemeColorPickerScreen(
    initialColor: String,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit
) {
    // 初始色：解析失败时用主题默认紫。
    val init = parseHex(initialColor) ?: Color(0xFF6750A4)
    val initHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(init.toArgb(), it) }

    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2].coerceIn(0.05f, 1f)) }

    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(title = "主题色", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ---- 预览 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(current)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "当前颜色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = current.toHex(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ---- 色域方块 ----
            var canvasWidth by remember { mutableFloatStateOf(1f) }
            var canvasHeight by remember { mutableFloatStateOf(1f) }

            fun updateFromOffset(offset: Offset) {
                val x = (offset.x / canvasWidth).coerceIn(0f, 1f)
                val y = (offset.y / canvasHeight).coerceIn(0f, 1f)
                hue = x * 360f
                sat = 1f - y
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .onSizeChanged {
                        canvasWidth = it.width.toFloat().coerceAtLeast(1f)
                        canvasHeight = it.height.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> updateFromOffset(offset) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            updateFromOffset(change.position)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                // 底色：纯色相横条（饱和度 1）。
                val hueColors = (0..36).map {
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 10f, 1f, 1f)))
                }
                drawRect(
                    brush = Brush.horizontalGradient(hueColors),
                    size = size
                )
                // 叠加：从上到下白 → 透明（降饱和）。
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White, Color.Transparent)
                    ),
                    size = size
                )

                // 当前点标记（按屏幕色相坐标绘制，保证在色域内可见）。
                val px = (hue / 360f) * w
                val py = (1f - sat) * h
                drawCircle(
                    color = Color.White,
                    radius = 10f,
                    center = Offset(px, py),
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 11.5f,
                    center = Offset(px, py),
                    style = Stroke(width = 1.5f)
                )
            }

            // ---- 亮度滑条 ----
            var barWidth by remember { mutableFloatStateOf(1f) }

            fun updateValue(offsetX: Float) {
                value = (offsetX / barWidth).coerceIn(0f, 1f)
            }

            val darkV = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0f)))
            val lightV = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .onSizeChanged { barWidth = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> updateValue(offset.x) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> updateValue(change.position.x) }
                    }
            ) {
                drawRect(brush = Brush.horizontalGradient(listOf(darkV, lightV)), size = size)
                val cx = value * size.width
                drawCircle(
                    color = Color.White,
                    radius = size.height / 2f - 2f,
                    center = Offset(cx, size.height / 2f),
                    style = Stroke(width = 3f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ---- 操作按钮 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onConfirm("") },
                    modifier = Modifier.weight(1f)
                ) { Text("恢复默认") }

                Button(
                    onClick = { onConfirm(current.toHex()) },
                    modifier = Modifier.weight(1f)
                ) { Text("应用") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// hex 工具
// ---------------------------------------------------------------------------

private fun Color.toHex(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun parseHex(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(0xFF000000.toInt() or s.toLong(16).toInt())
            8 -> Color(s.toLong(16).toInt())
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
