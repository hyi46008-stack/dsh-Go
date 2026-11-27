package io.github.ccch1mneyyy.dsh_app.voice

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * 唤醒词的 Flutter 侧接口：`dsh/wake`（方法）与 `dsh/wake/events`（事件）。
 *
 * 方法：start(keyword) / stop() / pause() / resume() / status()
 * 事件：{ "type": "wake", "text": 听到了什么 }，以及 { "type": "state", "running": 布尔 }
 */
class WakeWordPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var context: Context? = null
    private var sink: EventChannel.EventSink? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val ctx = binding.applicationContext
        context = ctx
        methods = MethodChannel(binding.binaryMessenger, "dsh/wake").also { it.setMethodCallHandler(this) }
        events = EventChannel(binding.binaryMessenger, "dsh/wake/events").also { it.setStreamHandler(this) }
        WakeWordService.onWake = { heard ->
            sink?.let { s ->
                main.post { runCatching { s.success(mapOf("type" to "wake", "text" to heard)) } }
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        WakeWordService.onWake = null
        methods?.setMethodCallHandler(null)
        methods = null
        events?.setStreamHandler(null)
        events = null
        sink = null
        context = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        events?.success(mapOf("type" to "state", "running" to WakeWordService.running))
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("no_context", "plugin has no context", null)
            return
        }
        when (call.method) {
            "start" -> {
                val keyword = call.argument<String>("keyword")?.takeIf { it.isNotBlank() } ?: "小黄鱼"
                WakeWordService.start(ctx, keyword)
                result.success(true)
            }
            "stop" -> {
                WakeWordService.send(ctx, WakeWordService.ACTION_STOP)
                result.success(true)
            }
            "pause" -> {
                WakeWordService.send(ctx, WakeWordService.ACTION_PAUSE)
                result.success(true)
            }
            "resume" -> {
                WakeWordService.send(ctx, WakeWordService.ACTION_RESUME)
                result.success(true)
            }
            "status" -> result.success(mapOf("running" to WakeWordService.running))
            else -> result.notImplemented()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
}
