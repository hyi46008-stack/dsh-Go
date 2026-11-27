package io.github.ccch1mneyyy.dsh_app.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 调试用：`adb shell am broadcast -a ...DEBUG_SUBTITLE --es text "..."` 直接在屏幕上试字幕条，
 * 不用对着麦克风喊。正式发给用户的包里保留也无害（只有本机 ADB 能发）。
 */
class DebugSubtitleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                val text = intent.getStringExtra("text") ?: "小黄鱼字幕测试"
                Log.i("DshSubtitle", "broadcast show: $text")
                SubtitleOverlay.show(context, text)
            }
            ACTION_HIDE -> SubtitleOverlay.hide()
        }
    }

    companion object {
        const val ACTION_SHOW = "io.github.ccch1mneyyy.dsh_app.DEBUG_SUBTITLE"
        const val ACTION_HIDE = "io.github.ccch1mneyyy.dsh_app.DEBUG_SUBTITLE_HIDE"
    }
}
