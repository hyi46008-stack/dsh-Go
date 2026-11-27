package io.github.ccch1mneyyy.dsh_app.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R

/**
 * 小黄鱼语音唤醒：只做一件事 —— 在 App 退到后台时听有没有人喊唤醒词。
 *
 * 为什么只在后台听：前台时输入框的听写、语音通话都要用麦克风，而系统识别器是独占的。
 * 前台交给它们，后台才轮到这里；一旦喊中，就把 App 拉回前台，自己立刻闭嘴把麦克风让出去。
 *
 * 用的是系统 SpeechRecognizer 的流式部分结果，不额外引模型、不需要 AccessKey。
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_START = "dsh.wake.start"
        const val ACTION_STOP = "dsh.wake.stop"
        const val ACTION_PAUSE = "dsh.wake.pause"
        const val ACTION_RESUME = "dsh.wake.resume"
        const val EXTRA_KEYWORD = "keyword"

        private const val CHANNEL_ID = "dsh_wake_word"
        private const val NOTIFICATION_ID = 4711
        private const val RESTART_DELAY_MS = 600L
        private const val COOLDOWN_MS = 8_000L

        /** 每次识别到唤醒词就回调一次（由 WakeWordPlugin 负责送到 Dart）。 */
        @Volatile var onWake: ((String) -> Unit)? = null

        @Volatile var running = false
            private set

        fun start(context: Context, keyword: String) {
            val intent = Intent(context, WakeWordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KEYWORD, keyword)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, WakeWordService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var keyword = "小黄鱼"
    private var listening = false
    private var paused = true          // 前台时默认不抢麦
    private var cooldownUntil = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                running = false
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                stopListening()
                notify(getString(R.string.wake_word_notification_paused))
            }
            ACTION_RESUME -> {
                paused = false
                val wanted = intent.getStringExtra(EXTRA_KEYWORD)
                if (!wanted.isNullOrBlank()) keyword = wanted
                running = true
                begin()
                notify(getString(R.string.wake_word_notification_listening, keyword))
            }
            ACTION_START -> {
                val wanted = intent.getStringExtra(EXTRA_KEYWORD)
                if (!wanted.isNullOrBlank()) keyword = wanted
                running = true
                // 启动时 App 就在前台：先让麦，等退到后台再由 Dart 叫醒它。
                paused = true
                notify(getString(R.string.wake_word_notification_paused))
            }
        }
        return START_STICKY
    }

    private fun begin() {
        if (paused || listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notify(getString(R.string.wake_word_notification_no_permission))
            running = false
            return
        }
        if (System.currentTimeMillis() < cooldownUntil) {
            main.postDelayed({ begin() }, cooldownUntil - System.currentTimeMillis())
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notify(getString(R.string.wake_word_notification_unavailable))
            running = false
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        r.startListening(intent)
    }

    private fun stopListening() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.apply { runCatching { cancel() } }
    }

    private fun scheduleRestart(delay: Long = RESTART_DELAY_MS) {
        if (paused) return
        listening = false
        main.postDelayed({ begin() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            // 说话被打断 / 没听到就停是常态，稍等再开一轮；错误码本身不弹给用户。
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) 1_500L else RESTART_DELAY_MS)
        }

        override fun onResults(results: Bundle?) {
            inspect(results, final = true)
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (inspect(partialResults, final = false)) return
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 命中就收麦、把 App 拉回前台、进入冷却；返回是否命中。 */
    private fun inspect(bundle: Bundle?, final: Boolean): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        for (text in list) {
            if (text.contains(keyword) || normalize(text).contains(normalize(keyword))) {
                fire(text)
                return true
            }
        }
        return false
    }

    /** 识别结果偶尔带空格或标点，比较前压掉。 */
    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() || it in "，。！？、,.!?" }

    private fun fire(heard: String) {
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        stopListening()
        notify(getString(R.string.wake_word_notification_heard, keyword))
        runCatching { onWake?.invoke(heard) }
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("dsh-wake-word", heard)
        }
        runCatching { startActivity(open) }
        scheduleRestart(COOLDOWN_MS)
    }

    private fun buildNotification(text: String?): Notification {
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
            .setContentText(text ?: getString(R.string.wake_word_notification_listening, keyword))
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
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        recognizer?.apply { runCatching { destroy() } }
        recognizer = null
        running = false
        super.onDestroy()
    }
}
