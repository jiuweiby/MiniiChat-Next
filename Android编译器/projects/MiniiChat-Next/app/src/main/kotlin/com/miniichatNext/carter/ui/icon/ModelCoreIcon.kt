package com.miniichatNext.carter.ui.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手搓 SVG 模型核心矢量图标
 */
@Composable
fun rememberModelCoreIcon(): ImageVector {
    return remember {
        ImageVector.Builder(
            name = "ModelCoreIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 外轮廓：科技棱角六边形/晶圆核心
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2.5f)
                lineTo(20.5f, 7.5f)
                lineTo(20.5f, 16.5f)
                lineTo(12f, 21.5f)
                lineTo(3.5f, 16.5f)
                lineTo(3.5f, 7.5f)
                close()
            }
            // 内部立体棱线
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2.5f)
                lineTo(12f, 12f)
                moveTo(12f, 12f)
                lineTo(20.5f, 16.5f)
                moveTo(12f, 12f)
                lineTo(3.5f, 16.5f)
            }
            // 中心神经网络核心点
            path(
                fill = SolidColor(Color.White)
            ) {
                moveTo(12f, 10f)
                curveTo(13.1f, 10f, 14f, 10.9f, 14f, 12f)
                curveTo(14f, 13.1f, 13.1f, 14f, 12f, 14f)
                curveTo(10.9f, 14f, 10f, 13.1f, 10f, 12f)
                curveTo(10f, 10.9f, 10.9f, 10f, 12f, 10f)
                close()
            }
            // 晶体四方微粒子光晕点
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 5.5f)
                lineTo(12.8f, 6.8f)
                lineTo(14f, 7.5f)
                lineTo(12.8f, 8.2f)
                lineTo(12f, 9.5f)
                lineTo(11.2f, 8.2f)
                lineTo(10f, 7.5f)
                lineTo(11.2f, 6.8f)
                close()
            }
        }.build()
    }
}
