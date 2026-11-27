package io.github.ccch1mneyyy.dsh_app.voice

import android.content.Context
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** 小黄鱼字幕悬浮条的 Flutter 侧接口：`dsh/subtitle`（show / update / hide / allowed）。 */
class SubtitlePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private var context: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "dsh/subtitle").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("no_context", "plugin has no context", null)
            return
        }
        when (call.method) {
            "show" -> {
                SubtitleOverlay.show(ctx, call.argument<String>("text").orEmpty())
                result.success(true)
            }
            "update" -> {
                SubtitleOverlay.update(ctx, call.argument<String>("text").orEmpty())
                result.success(true)
            }
            "hide" -> {
                SubtitleOverlay.hide()
                result.success(true)
            }
            "allowed" -> result.success(Settings.canDrawOverlays(ctx))
            else -> result.notImplemented()
        }
    }
}
