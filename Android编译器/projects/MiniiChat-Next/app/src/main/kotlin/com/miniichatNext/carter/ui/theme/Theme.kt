package com.miniichatNext.carter.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Clean iOS-style flat theme.
 * Pure white / near-black backgrounds, hairline dividers, accent purple for primary.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6E5CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FF),
    onPrimaryContainer = Color(0xFF170B5C),
    secondary = Color(0xFF6E5CFF),
    onSecondary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF3F2F7),
    onSurfaceVariant = Color(0xFF6F6E78),
    outline = Color(0xFFE3E1EA),
    outlineVariant = Color(0xFFEEEDF2),
    error = Color(0xFFE34864),
    onError = Color.White
)

// Tightened dark scheme: lighter onSurfaceVariant for legibility, slightly bolder
// surface contrast so subtle UI (chips, dividers, hint text) reads cleanly.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1A4FF),
    onPrimary = Color(0xFF15093D),
    primaryContainer = Color(0xFF3826A8),
    onPrimaryContainer = Color(0xFFEDE7FF),
    secondary = Color(0xFFB1A4FF),
    onSecondary = Color(0xFF15093D),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F1F7),
    surface = Color(0xFF101013),
    onSurface = Color(0xFFF2F1F7),
    surfaceVariant = Color(0xFF24242C),
    onSurfaceVariant = Color(0xFFC4C2D0),
    outline = Color(0xFF3A3A45),
    outlineVariant = Color(0xFF26262E),
    error = Color(0xFFFF8FA0),
    onError = Color(0xFF3D0011)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

@Composable
fun MiniiChatTheme(
    themeMode: String = "system", // system | light | dark
    dynamicColor: Boolean = true,
    accentColor: String = "",     // 自定义主题色 #RRGGBB，空串表示未启用
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val baseColors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val dyn = if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            // Material You's generated dark scheme can produce very low-contrast
            // onSurfaceVariant against its surface, especially under cool-toned
            // wallpapers. Override the most legibility-critical roles to keep
            // chip labels, hints, and divider tints readable while still using
            // the wallpaper-derived hue for primary / containers.
            if (darkTheme) {
                dyn.copy(
                    background = Color(0xFF000000),
                    onBackground = Color(0xFFF2F1F7),
                    surface = Color(0xFF101013),
                    onSurface = Color(0xFFF2F1F7),
                    surfaceVariant = Color(0xFF24242C),
                    onSurfaceVariant = Color(0xFFC4C2D0),
                    outline = Color(0xFF3A3A45),
                    outlineVariant = Color(0xFF26262E)
                )
            } else dyn
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    // 自定义主题色优先级最高：覆盖在动态取色 / 默认配色之后。
    val accent = parseHexColor(accentColor)
    val colors = if (accent != null) applyAccent(baseColors, accent, darkTheme) else baseColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}

// ---------------------------------------------------------------------------
// 自定义主题色支持
// ---------------------------------------------------------------------------

/** 解析 "#RRGGBB" / "RRGGBB" / "#AARRGGBB"，失败返回 null。 */
internal fun parseHexColor(hex: String): Color? {
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

/** 将自定义色作为 primary 家族应用到配色方案上。 */
private fun applyAccent(base: androidx.compose.material3.ColorScheme, accent: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]

    // 由主色派生容器色与前景色，保持可读对比。
    val primary = if (dark) accent.lightenForDark() else accent.darkenForLight()
    val onPrimary = if (isLight(primary)) Color(0xFF101013) else Color.White
    val primaryContainer = Color.hsv(hue, (sat * 0.35f).coerceIn(0f, 1f), if (dark) 0.28f else 0.94f)
    val onPrimaryContainer = Color.hsv(hue, (sat * 0.55f).coerceIn(0f, 1f), if (dark) 0.92f else 0.18f)
    val secondary = primary
    val onSecondary = onPrimary

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer
    )
}

private fun Color.lightenForDark(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[2] = (hsv[2] * 0.55f + 0.45f).coerceIn(0f, 1f)
    hsv[1] = (hsv[1] * 0.75f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun Color.darkenForLight(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[2] = (hsv[2] * 0.9f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun isLight(c: Color): Boolean {
    val r = c.red * 255f; val g = c.green * 255f; val b = c.blue * 255f
    return (0.299 * r + 0.587 * g + 0.114 * b) > 160f
}
