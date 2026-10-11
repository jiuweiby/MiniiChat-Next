package com.miniichatNext.carter.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 根据模型名/提供商名从 assets/icons 中模糊自动匹配对应的官方品牌 SVG 图标路径
 */
fun findModelAssetIcon(identifier: String?): String? {
    if (identifier.isNullOrBlank()) return null
    val s = identifier.lowercase().trim()

    return when {
        // OpenAI / GPT 系列（无论任何前缀后缀，例如 gpt-4o, chatgpt, openai-o1 等）
        s.contains("openai") || s.contains("gpt") || s.contains("o1") || s.contains("o3") -> "icons/openai.svg"
        // DeepSeek
        s.contains("deepseek") -> "icons/deepseek-color.svg"
        // Claude / Anthropic
        s.contains("claude") || s.contains("anthropic") -> "icons/claude-color.svg"
        // Google Gemini
        s.contains("gemini") -> "icons/gemini-color.svg"
        // Gemma
        s.contains("gemma") -> "icons/gemma-color.svg"
        // Google
        s.contains("google") -> "icons/google-color.svg"
        // Grok / xAI
        s.contains("grok") || s.contains("xai") -> "icons/grok.svg"
        // 豆包 (Doubao) / 字节跳动
        s.contains("doubao") || s.contains("bytedance") -> "icons/doubao-color.svg"
        // 腾讯混元 (Hunyuan)
        s.contains("hunyuan") || s.contains("tencent") -> "icons/hunyuan-color.svg"
        // Kimi / 月之暗面
        s.contains("kimi") -> "icons/kimi-color.svg"
        s.contains("moonshot") -> "icons/moonshot.svg"
        // MiniMax
        s.contains("minimax") || s.contains("abab") -> "icons/minimax-color.svg"
        // Mistral
        s.contains("mistral") || s.contains("mixtral") -> "icons/mistral-color.svg"
        // NVIDIA
        s.contains("nvidia") || s.contains("nemotron") -> "icons/nvidia-color.svg"
        // Ollama
        s.contains("ollama") -> "icons/ollama.svg"
        // 通义千问 (Qwen) / 阿里
        s.contains("qwen") || s.contains("alibaba") -> "icons/qwen-color.svg"
        // 小米 MIMO
        s.contains("mimo") || s.contains("xiaomi") -> "icons/xiaomimimo.svg"
        // 智谱 GLM (Zhipu)
        s.contains("zhipu") || s.contains("glm") || s.contains("chatglm") -> "icons/zhipu-color.svg"
        else -> null
    }
}

@Composable
fun AvatarView(
    avatar: Avatar,
    fallbackInitial: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    modelIdentifier: String? = null // 传入模型名或提供商名称，自动匹配 assets/icons 下的 svg
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        val matchedAsset = remember(modelIdentifier) { findModelAssetIcon(modelIdentifier) }
        val context = LocalContext.current

        if (matchedAsset != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/$matchedAsset")
                    .decoderFactory(SvgDecoder.Factory())
                    .crossfade(true)
                    .build(),
                contentDescription = modelIdentifier,
                modifier = Modifier
                    .size(size * 0.82f)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
        } else {
            val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
            when (avatar) {
                is Avatar.Image -> {
                    val bitmap = rememberBitmap(avatar.path, targetPx)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(size),
                            contentScale = ContentScale.Crop
                        )
                    } else if (fallbackInitial.isNotBlank()) {
                        Text(
                            text = fallbackInitial.take(1).uppercase(),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (size.value * 0.45f).sp,
                            color = LocalContentColor.current
                        )
                    }
                }
                is Avatar.Emoji -> {
                    if (avatar.content == "🤖" || avatar.content.isBlank()) {
                        RobotSvgIcon(
                            modifier = Modifier.size(size * 0.72f),
                            primaryColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = avatar.content,
                            fontSize = (size.value * 0.55f).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                is Avatar.None -> {
                    if (fallbackInitial.equals("M", ignoreCase = true) || fallbackInitial.isBlank()) {
                        RobotSvgIcon(
                            modifier = Modifier.size(size * 0.72f),
                            primaryColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = fallbackInitial.take(1).uppercase(),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (size.value * 0.45f).sp,
                            color = LocalContentColor.current
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarView(
    avatar: String,
    avatarPath: String?,
    fallbackInitial: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    modelIdentifier: String? = null
) {
    val a = Avatar.fromLegacy(avatar, avatarPath)
    AvatarView(a, fallbackInitial, modifier, size, background, modelIdentifier)
}

@Composable
private fun rememberBitmap(path: String?, targetPx: Int): ImageBitmap? {
    var bitmap by remember(path, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, targetPx) {
        if (path.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val file = java.io.File(path)
        var decoded: ImageBitmap? = null
        for (attempt in 0 until 3) {
            if (!file.exists() || file.length() <= 0L) {
                delay(140L)
                continue
            }
            decoded = withContext(Dispatchers.IO) {
                runCatching { decodeSampled(path, targetPx)?.asImageBitmap() }.getOrNull()
            }
            if (decoded != null) break
            delay(180L)
        }
        if (decoded == null) {
            if (!file.exists()) {
                DebugLog.w("AvatarView", "avatar file missing: $path")
            } else {
                DebugLog.e("AvatarView", "avatar decode failed: $path (${file.length()} bytes, target=${targetPx}px)")
            }
        } else {
            DebugLog.v("AvatarView", "avatar loaded: $path (${file.length()} bytes, ${decoded.width}x${decoded.height})")
        }
        bitmap = decoded
    }
    return bitmap
}

private fun decodeSampled(path: String, targetPx: Int): android.graphics.Bitmap? {
    if (targetPx <= 0) return BitmapFactory.decodeFile(path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx &&
        bounds.outHeight / (sample * 2) >= targetPx
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, options)
}
