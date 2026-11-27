package io.github.ccch1mneyyy.dsh_app.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 小黄鱼的字幕悬浮条：贴在屏幕底部的一条半透明发光字幕，不抢焦点、不挡触摸。
 *
 * 用系统悬浮窗（TYPE_APPLICATION_OVERLAY），需要「显示悬浮窗」权限；App 在后台、
 * 别的应用在前台、甚至锁屏时都能显示。翻页/淡入淡出都在这里做，Flutter 只管给字。
 */
object SubtitleOverlay {
    private var root: FrameLayout? = null
    private var label: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /** 第一次显示时把窗建起来；没有悬浮窗权限就静默失败（返回 null）。 */
    private fun ensure(context: Context): TextView? {
        root?.let { return label }
        val ctx = context.applicationContext
        return runCatching {
            val text = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(ctx, 22), dp(ctx, 12), dp(ctx, 22), dp(ctx, 12))
                // 发光描边：青色光晕，读起来像一条会呼吸的字幕。
                setShadowLayer(dp(ctx, 5).toFloat(), 0f, 0f, Color.parseColor("#B300E5FF"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#D90A1018"))
                    cornerRadius = dp(ctx, 20).toFloat()
                    setStroke(dp(ctx, 1), Color.parseColor("#8000E5FF"))
                }
                maxLines = 4
                // 先给一句占位字：wrap_content 的窗口要有内容才有高度，否则显示时会量成 0。
                text = "小黄鱼在听…"
                visibility = View.VISIBLE
                alpha = 0f
            }
            val frame = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0)
                addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                alpha = 0f
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, 150)
            }
            wm.addView(frame, lp)
            root = frame
            label = text
            Log.i("DshSubtitle", "overlay added")
            text
        }.getOrNull()
    }

    fun show(context: Context, text: String) = main.post {
        val view = ensure(context) ?: return@post
        view.text = text
        val frame = root ?: return@post
        Log.i("DshSubtitle", "show: " + text)
        frame.animate().cancel()
        frame.translationY = dp(context, 26).toFloat()
        frame.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    fun update(context: Context, text: String) = main.post {
        val view = label ?: return@post
        if (view.text.toString() == text) return@post
        view.text = text
        val frame = root ?: return@post
        if (frame.alpha < 1f) {
            frame.animate().cancel()
            frame.animate().alpha(1f).setDuration(160).start()
        }
    }

    fun hide() = main.post {
        val frame = root ?: return@post
        frame.animate().cancel()
        frame.animate().alpha(0f).translationY(dp(frame.context, 26).toFloat()).setDuration(200)
            .withEndAction { frame.alpha = 0f }
            .start()
    }
}
