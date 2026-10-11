package com.miniichatNext.carter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 强效前台保活服务：
 * 1. 状态栏持续前台通知
 * 2. 极微不可见透明悬浮窗（不可点击透传）
 * 3. 无声音频静音流播放
 */
class KeepAliveService : Service {

    constructor() : super()

    companion object {
        private const val CHANNEL_ID = "channel_keep_alive"
        private const val NOTIF_ID = 9527

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var audioTrack: AudioTrack? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlayWindow()
        startSilentAudio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "后台保活通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持模型在后台持续响应与接收流式回复"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("后台保活运行中，持续接收 AI 回复")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupOverlayWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                return
            }
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                1, 1,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
                alpha = 0f
            }

            val view = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            windowManager?.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
        }
    }

    private fun startSilentAudio() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val buffer = ByteArray(minBufSize.coerceAtLeast(1024))

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                while (isActive) {
                    audioTrack?.write(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        overlayView?.let {
            runCatching { windowManager?.removeView(it) }
        }
        overlayView = null
        super.onDestroy()
    }
}
