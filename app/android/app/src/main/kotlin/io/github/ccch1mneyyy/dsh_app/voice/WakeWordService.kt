package io.github.ccch1mneyyy.dsh_app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R

/**
 * 小黄鱼语音唤醍的前台服务：只做「让进程活着 + 挂着通知」这一件事。
 *
 * 听和识别由 Dart 侧用应用自己的录音通道 + 本地语音模型完成（voice/wake_word.dart），
 * 因为系统语音识别服务在后台直接拒绝给本应用开麦（"caller doesn't have permission"）。
 * 前台时这个服务只是待命：麦克风归听写和语音通话。
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_START = "dsh.wake.start"
        const val ACTION_STOP = "dsh.wake.stop"
        const val ACTION_LISTENING = "dsh.wake.listening"
        const val ACTION_IDLE = "dsh.wake.idle"

        private const val CHANNEL_ID = "dsh_wake_word"
        private const val NOTIFICATION_ID = 4711

        @Volatile var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun send(context: Context, action: String) {
            runCatching { context.startService(Intent(context, WakeWordService::class.java).setAction(action)) }
        }

        /** 喊中之后把 App 拉回前台（targetSdk 28 下后台启动 Activity 仍被允许）。 */
        fun bringToFront(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    },
                )
            }
        }
    }

    private var listening = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.wake_word_notification_idle)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTENING -> {
                listening = true
                notify(getString(R.string.wake_word_notification_listening, "小黄鱼"))
            }
            ACTION_IDLE -> {
                listening = false
                notify(getString(R.string.wake_word_notification_paused))
            }
            else -> {
                running = true
                notify(if (listening) getString(R.string.wake_word_notification_listening, "小黄鱼") else getString(R.string.wake_word_notification_idle))
            }
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.wake_word_channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(R.string.wake_word_notification_stop),
                    stop,
                ).build(),
            )
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
