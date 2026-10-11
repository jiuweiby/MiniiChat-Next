package com.miniichatNext.carter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手搓精美 Vector / SVG 风格的机器人图标组件
 * 完全脱离系统 emoji 依赖，拥有清晰的官方科技感线条与天线细节
 */
@Composable
fun RobotSvgIcon(
    modifier: Modifier = Modifier.size(64.dp),
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 比例常数
        val headLeft = w * 0.20f
        val headTop = h * 0.28f
        val headW = w * 0.60f
        val headH = h * 0.48f
        val cornerRad = w * 0.12f

        // 1. 顶部天线杆与小天线球
        val antennaTop = h * 0.10f
        val antennaBallRadius = w * 0.055f
        drawCircle(
            color = primaryColor,
            radius = antennaBallRadius,
            center = Offset(w * 0.50f, antennaTop)
        )
        drawLine(
            color = primaryColor,
            start = Offset(w * 0.50f, antennaTop + antennaBallRadius),
            end = Offset(w * 0.50f, headTop),
            strokeWidth = w * 0.035f
        )

        // 2. 左右两侧小耳朵
        val earW = w * 0.06f
        val earH = h * 0.18f
        val earY = headTop + headH * 0.32f
        // 左耳
        drawRoundRect(
            color = secondaryColor.copy(alpha = 0.8f),
            topLeft = Offset(headLeft - earW, earY),
            size = Size(earW, earH),
            cornerRadius = CornerRadius(earW * 0.5f, earW * 0.5f)
        )
        // 右耳
        drawRoundRect(
            color = secondaryColor.copy(alpha = 0.8f),
            topLeft = Offset(headLeft + headW, earY),
            size = Size(earW, earH),
            cornerRadius = CornerRadius(earW * 0.5f, earW * 0.5f)
        )

        // 3. 机器人头部主体圆角矩形
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.16f),
            topLeft = Offset(headLeft, headTop),
            size = Size(headW, headH),
            cornerRadius = CornerRadius(cornerRad, cornerRad)
        )
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(headLeft, headTop),
            size = Size(headW, headH),
            cornerRadius = CornerRadius(cornerRad, cornerRad),
            style = Stroke(width = w * 0.032f)
        )

        // 4. 面部护目屏背景
        val screenLeft = headLeft + headW * 0.12f
        val screenTop = headTop + headH * 0.18f
        val screenW = headW * 0.76f
        val screenH = headH * 0.46f
        val screenRad = cornerRad * 0.6f
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.28f),
            topLeft = Offset(screenLeft, screenTop),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(screenRad, screenRad)
        )

        // 5. 两个发光眼睛
        val eyeRadius = w * 0.042f
        val eyeY = screenTop + screenH * 0.50f
        val eyeLeftX = screenLeft + screenW * 0.30f
        val eyeRightX = screenLeft + screenW * 0.70f

        drawCircle(
            color = primaryColor,
            radius = eyeRadius,
            center = Offset(eyeLeftX, eyeY)
        )
        drawCircle(
            color = primaryColor,
            radius = eyeRadius,
            center = Offset(eyeRightX, eyeY)
        )

        // 6. 机器人嘴部网格 / 微笑线条
        val mouthY = headTop + headH * 0.80f
        val mouthW = headW * 0.36f
        val mouthLeft = headLeft + (headW - mouthW) / 2f

        drawLine(
            color = primaryColor.copy(alpha = 0.9f),
            start = Offset(mouthLeft, mouthY),
            end = Offset(mouthLeft + mouthW, mouthY),
            strokeWidth = w * 0.026f
        )
    }
}
