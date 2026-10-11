package com.miniichatNext.carter.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.ui.components.AvatarPicker
import com.miniichatNext.carter.ui.components.AvatarView
import com.miniichatNext.carter.ui.icon.rememberModelCoreIcon
import androidx.compose.material3.Icon

/**
 * 首次进入全屏欢迎与资料引导页：
 * - 动态缩放欢迎动画
 * - 必须输入名字（提示"如何称呼您？"）
 * - 选择个性头像
 */
@Composable
fun WelcomeOnboardingDialog(
    initialProfile: UserProfile,
    onFinish: (UserProfile) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var currentAvatar by remember { mutableStateOf(initialProfile.avatar) }
    var showPicker by remember { mutableStateOf(false) }
    var hasAttemptedSubmit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.85f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 顶端手搓晶体微标
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = rememberModelCoreIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "欢迎使用 MiniiChat Next",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "在开始前，请完善您的个人称谓与头像",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    // 头像选择
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .clickable { showPicker = true }
                    ) {
                        AvatarView(
                            avatar = currentAvatar,
                            fallbackInitial = nameInput.take(1).ifBlank { "U" },
                            size = 80.dp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "点击更换头像",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(20.dp))

                    // 必须输入称呼
                    val isError = hasAttemptedSubmit && nameInput.trim().isBlank()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "如何称呼您？",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .border(
                                    1.2.dp,
                                    if (isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            if (nameInput.isEmpty()) {
                                Text(
                                    text = "请输入您的昵称…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            BasicTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                            )
                        }
                        if (isError) {
                            Text(
                                text = "名字不能为空，请输入昵称",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(26.dp))

                    Button(
                        onClick = {
                            hasAttemptedSubmit = true
                            if (nameInput.trim().isNotBlank()) {
                                onFinish(
                                    initialProfile.copy(
                                        displayName = nameInput.trim(),
                                        avatar = currentAvatar
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("进入应用", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }

    if (showPicker) {
        AvatarPicker(
            current = currentAvatar,
            onChange = {
                currentAvatar = it
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
