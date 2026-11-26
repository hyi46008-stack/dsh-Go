package io.github.ccch1mneyyy.dsh_app.nativemedia

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

/**
 * Native plugins' v1.2 capabilities (docs/design/native-plugins-protocol.md §11): MethodChannel
 * `dsh/native-media`, EventChannel `dsh/native-media/events`. The Dart side
 * (lib/plugins/native/native_capabilities.dart) decides what may run; this side owns the
 * player, the microphone and the views, and stops them on its own.
 *
 * One owner at a time — the token, generation and bridge lease Dart sends with `activate`; every
 * other call names it, and calls for any other owner fail with `stale-owner`. Nothing is created
 * before a call asks for it: without a declaring plugin Dart never calls in at all.
 *
 * Speech while a clip plays: the capture thread's edge reaches the main thread, the clip is
 * released here, and only then does `voice.speechStart` go to Dart, with `localStopped: true`.
 *
 * This side ends an owner by itself — the indicator's 停止, dictation taking the microphone, the
 * activity leaving the screen, Dart going away — and reports `owner.revoked`; Dart then tells the
 * plugin. Methods: capabilities, activate, play, pause, resume, stop, requestVoiceGrant,
 * startVoice, stopVoice, showOverlay, updateOverlay, hideOverlay, disposeOwner.
 */
class NativeMediaPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    PluginRegistry.RequestPermissionsResultListener {

    private class Owner(val token: String, val generation: Int, val lease: String, val pluginTitle: String)

    private lateinit var appContext: Context
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private val main = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var binding: ActivityPluginBinding? = null
    private var activity: Activity? = null
    private var stopped = false
    private var owner: Owner? = null
    private var pendingGrant: Pair<Owner, MethodChannel.Result>? = null

    private var player: ClipPlayer? = null
    private var capture: VoiceCapture? = null
    // v1.4 `mic`: the same capture, without a gate, its frames going to Dart.
    private var micActive = false
    @Volatile private var micSeq = 0
    // Bounded hand-off capture → main thread; then a credit window limits what Dart has not
    // acknowledged, so a stalled consumer stops the producer instead of growing a buffer.
    private val micQueue = MicQueue(MIC_QUEUE)
    private var micCredits = 0
    // The one VoiceCapture instance serves one mode: speech gates or PCM frames.
    private var framesMode = false
    private val overlays = MediaOverlays(onStop = { revoke("user") }, onCaptionClosed = { emitCurrent("overlay.closed", "user") })
    private val micHolder = MicArbiter.Holder { revoke("dictation") }

    private var probe: BroadcastReceiver? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "dsh/native-media").also { it.setMethodCallHandler(this) }
        events = EventChannel(binding.binaryMessenger, "dsh/native-media/events").also { it.setStreamHandler(this) }
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            probe = NativeMediaProbe.register(appContext, ::onSpeechEdge, ::debugState)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        probe?.let { runCatching { appContext.unregisterReceiver(it) } }
        probe = null
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        revoke("detached")
        sink = null
    }

    // ---- activity ------------------------------------------------------------------------------

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            if (a === activity) stopped = false
        }

        override fun onActivityStopped(a: Activity) {
            if (a !== activity) return
            stopped = true
            // Off the screen (another app, the lock screen): stop here and now.
            revoke("background")
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        activity = binding.activity
        stopped = false
        binding.addRequestPermissionsResultListener(this)
        (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        overlays.attach(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity(configChange = true)

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromActivity() = detachActivity(configChange = false)

    private fun detachActivity(configChange: Boolean) {
        binding?.removeRequestPermissionsResultListener(this)
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        binding = null
        activity = null
        overlays.attach(null)
        pendingGrant?.second?.success(false)
        pendingGrant = null
        if (!configChange) revoke("detached")
    }

    private fun onScreen() = activity != null && !stopped

    // ---- channel -------------------------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "capabilities") {
            val mic = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            result.success(mapOf("features" to listOfNotNull("audio", "overlay", if (mic) "voice" else null, if (mic) "mic" else null)))
            return
        }
        if (call.method == "activate") return activate(call, result)
        val o = owner
        if (o == null || call.argument<String>("owner") != o.token || call.argument<Int>("generation") != o.generation ||
            call.argument<String>("lease") != o.lease
        ) {
            // An owner that is already gone is already disposed.
            if (call.method == "disposeOwner") result.success(null) else result.error("stale-owner", "not the current owner", null)
            return
        }
        when (call.method) {
            "play" -> play(o, call, result)
            "pause" -> {
                val p = player
                val wasPlaying = p?.playing == true
                val done = p?.pause() == true
                if (done && wasPlaying) capture?.setPlaying(false)
                ok(result, done, "audio")
            }
            "resume" -> {
                val p = player
                val done = onScreen() && p?.resume() == true
                if (done && p.playing == true) capture?.setPlaying(true)
                ok(result, done, "audio")
            }
            "stop" -> {
                capture?.setPlaying(false)
                ok(result, player?.release() != false, "close")
            }
            "requestVoiceGrant" -> requestGrant(o, result)
            "startVoice" -> startVoice(o, result)
            "stopVoice" -> ok(result, stopVoice(), "close")
            "startMic" -> startMic(o, result)
            "micAck" -> {
                val count = call.argument<Int>("count") ?: 0
                micCredits = minOf(MIC_WINDOW, micCredits + count.coerceIn(0, MIC_WINDOW))
                result.success(null)
            }
            // The terminal event is Dart's: it reports mic.stopped after this call closes the recorder.
            "stopMic" -> ok(result, stopVoice(), "close")
            "showOverlay" -> showOverlay(call, result, update = false)
            "updateOverlay" -> showOverlay(call, result, update = true)
            "hideOverlay" -> {
                overlays.hideCaption()
                result.success(null)
            }
            "disposeOwner" -> ok(result, disposeLocal(), "close")
            else -> result.notImplemented()
        }
    }

    private fun ok(result: MethodChannel.Result, done: Boolean, code: String) {
        if (done) result.success(null) else result.error(code, "not done", null)
    }

    private fun activate(call: MethodCall, result: MethodChannel.Result) {
        val token = call.argument<String>("owner")
        val generation = call.argument<Int>("generation")
        val lease = call.argument<String>("lease")
        if (token == null || generation == null || lease == null || lease.length > 256) {
            return result.error("bad-request", "owner, generation and lease are required", null)
        }
        if (!onScreen()) return result.error("not-foreground", "the app is not on screen", null)
        // A new owner replaces the old one, which Dart has disposed (or is disposing) already.
        disposeLocal()
        val title = call.argument<String>("pluginTitle")?.take(MAX_TITLE)?.ifBlank { null } ?: call.argument<String>("pkg") ?: "plugin"
        owner = Owner(token, generation, lease, title)
        call.argument<Double>("top")?.let { overlays.setTop(it.toFloat()) }
        result.success(null)
    }

    private fun play(o: Owner, call: MethodCall, result: MethodChannel.Result) {
        if (!onScreen()) return result.error("not-foreground", "the app is not on screen", null)
        val file = ClipPath.allowed(
            appContext.cacheDir,
            o.token,
            call.argument<String>("privatePath") ?: "",
            call.argument<String>("contentType") ?: "",
        ) ?: return result.error("media-invalid", "not a clip of this owner", null)
        val p = player ?: ClipPlayer(appContext, playerEvents).also { player = it }
        if (!p.play(file)) return result.error("media-invalid", "the clip could not be opened", null)
        result.success(null)
    }

    private val playerEvents = object : ClipPlayer.Listener {
        override fun onStarted() {
            capture?.setPlaying(true)
        }

        override fun onEnded() {
            capture?.setPlaying(false)
            emitCurrent("audio.ended")
        }

        override fun onStopped(reason: String) {
            capture?.setPlaying(false)
            emitCurrent("audio.stopped", reason)
        }

        override fun onError(reason: String) {
            capture?.setPlaying(false)
            emitCurrent("audio.error", reason)
        }
    }

    // ---- listening -----------------------------------------------------------------------------

    private fun hasMicPermission() =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Android's RECORD_AUDIO (asked by the system once; the app's own consent comes first, in Dart). */
    private fun requestGrant(o: Owner, result: MethodChannel.Result) {
        if (hasMicPermission()) return result.success(true)
        val host = activity
        if (host == null || !onScreen() || pendingGrant != null) return result.success(false)
        pendingGrant = o to result
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val (o, result) = pendingGrant ?: return true
        pendingGrant = null
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // Answered after the owner changed or the app left: no.
        result.success(granted && owner === o && onScreen())
        return true
    }

    private fun startVoice(o: Owner, result: MethodChannel.Result) {
        micActive = false
        if (!onScreen()) return result.error("not-foreground", "the app is not on screen", null)
        if (!hasMicPermission()) return result.error("permission", "RECORD_AUDIO is not granted", null)
        // A plugin's frame capture gives way to its own listening, and vice versa.
        if (capture?.active == true && !framesMode) return result.success(null)
        if (framesMode) capture?.stop()
        if (!MicArbiter.acquire(micHolder)) return result.error("busy", "dictation is using the microphone", null)
        // The indicator first: no listening without it.
        if (!overlays.showIndicator(o.pluginTitle)) {
            MicArbiter.release(micHolder)
            return result.error("indicator", "nothing to show the indicator in", null)
        }
        val c = voiceCapture(frames = false)
        if (c == null) {
            overlays.hideIndicator()
            MicArbiter.release(micHolder)
            return result.error("stopping", "the previous capture is still closing", null)
        }
        if (!c.start()) {
            overlays.hideIndicator()
            MicArbiter.release(micHolder)
            return result.error("capture", "the microphone could not be opened", null)
        }
        c.setPlaying(player?.playing == true)
        Log.i(TAG, "listening: voice-communication source, echo canceller ${if (c.echoCancelled) "on" else "unavailable"}")
        result.success(null)
    }

    /** v1.4: PCM capture for one plugin lease — the indicator is the visible proof it runs. */
    private fun startMic(o: Owner, result: MethodChannel.Result) {
        if (!onScreen()) return result.error("not-foreground", "the app is not on screen", null)
        if (!hasMicPermission()) return result.error("permission", "RECORD_AUDIO is not granted", null)
        if (micActive) return result.success(null)
        if (!MicArbiter.acquire(micHolder)) return result.error("busy", "dictation is using the microphone", null)
        if (!overlays.showIndicator(o.pluginTitle)) {
            MicArbiter.release(micHolder)
            return result.error("indicator", "nothing to show the indicator in", null)
        }
        // The voice listener, if any, ends before the frames start.
        val c = voiceCapture(frames = true)
        if (c == null) {
            overlays.hideIndicator()
            MicArbiter.release(micHolder)
            return result.error("stopping", "the previous capture is still closing", null)
        }
        if (!c.start()) {
            overlays.hideIndicator()
            MicArbiter.release(micHolder)
            return result.error("capture", "the microphone could not be opened", null)
        }
        micActive = true
        micSeq = 0
        micQueue.clear()
        micCredits = MIC_WINDOW
        main.postDelayed(micPump, MIC_FRAME_MS)
        Log.i(TAG, "mic: 16 kHz mono PCM16 frames, echo canceller ${if (c.echoCancelled) "on" else "unavailable"}")
        result.success(null)
    }

    /**
     * One recorder, one mode: recreated when the mode changes, never shared. Null when the previous
     * thread has not exited yet — opening a second recorder over it is exactly what must not happen.
     */
    private fun voiceCapture(frames: Boolean): VoiceCapture? {
        val current = capture
        if (current != null && framesMode == frames) return current
        if (current != null && !current.stop()) return null
        return (if (frames) VoiceCapture(main, ::onSpeechEdge, ::onCaptureError, ::onMicFrame)
        else VoiceCapture(main, ::onSpeechEdge, ::onCaptureError)).also { capture = it; framesMode = frames }
    }

    /** Stops listening; the indicator goes only once the microphone is closed. */
    private fun stopVoice(): Boolean {
        micActive = false
        micQueue.clear()
        micCredits = 0
        main.removeCallbacks(micPump)
        val closed = capture?.stop() != false
        MicArbiter.release(micHolder)
        overlays.hideIndicator()
        return closed
    }

    private fun debugState(): String = "{owner=${owner?.generation}, player=${player?.active == true}, playing=${player?.playing == true}, " +
        "listening=${capture?.active == true}, echoCancelled=${capture?.echoCancelled == true}, indicator=${overlays.listening}, caption=${overlays.captionShown}}"

    private fun onSpeechEdge(edge: SpeechGate.Edge) {
        if (owner == null || capture?.active != true) return
        when (edge) {
            SpeechGate.Edge.START -> {
                // Stop the clip here first; only a released player is proof enough to report.
                val t0 = SystemClock.elapsedRealtimeNanos()
                val hadClip = player?.active == true
                capture?.setPlaying(false)
                if (player?.release() == false) {
                    revoke("player")
                    return
                }
                Log.i(TAG, "speech: ${if (hadClip) "clip released" else "no clip"} in ${(SystemClock.elapsedRealtimeNanos() - t0) / 1000} µs, then notify")
                emitCurrent("voice.speechStart", localStopped = true)
            }
            SpeechGate.Edge.END -> emitCurrent("voice.speechEnd")
            SpeechGate.Edge.NONE -> {}
        }
    }

    /** Capture thread: hand the frame to a bounded queue; never block, never allocate a task. */
    private fun onMicFrame(frame: ShortArray, count: Int) {
        if (!micActive) return
        micQueue.offer(MicQueue.Frame(++micSeq, MicPcm.bytes(frame, count)))
    }

    /** Main thread: send at most [MIC_PER_TICK] frames per tick while the credit window allows. */
    private val micPump = object : Runnable {
        override fun run() {
            if (!micActive) return
            val o = owner
            val s = sink
            if (o == null || s == null) {
                micQueue.clear()
            } else {
                var sent = 0
                while (sent < MIC_PER_TICK && micCredits > 0) {
                    val frame = micQueue.take() ?: break
                    micCredits--
                    s.success(
                        buildMap {
                            put("owner", o.token)
                            put("generation", o.generation)
                            put("lease", o.lease)
                            put("kind", "mic.frame")
                            put("seq", frame.seq)
                            put("bytes", frame.bytes)
                        },
                    )
                    sent++
                }
            }
            main.postDelayed(this, MIC_FRAME_MS)
        }
    }

    private fun onCaptureError(reason: String) {
        if (owner == null) return
        val wasMic = micActive
        stopVoice()
        emitCurrent(if (wasMic) "mic.error" else "voice.error", reason)
    }

    // ---- caption -------------------------------------------------------------------------------

    private fun showOverlay(call: MethodCall, result: MethodChannel.Result, update: Boolean) {
        val text = call.argument<String>("text") ?: ""
        if (text.codePointCount(0, text.length) > MAX_CAPTION_CHARS || text.toByteArray().size > MAX_CAPTION_BYTES) {
            return result.error("bad-request", "caption too long", null)
        }
        val clean = text.replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ").trim()
        if (update) return ok(result, overlays.updateCaption(clean), "unsupported")
        if (!onScreen()) return result.error("not-foreground", "the app is not on screen", null)
        ok(result, overlays.showCaption(clean), "unsupported")
    }

    // ---- ending --------------------------------------------------------------------------------

    /** Frees everything of the current owner; true when the player and the microphone are closed. */
    private fun disposeLocal(): Boolean {
        val wasOwner = owner
        owner = null
        if (pendingGrant?.first === wasOwner) {
            pendingGrant?.second?.success(false)
            pendingGrant = null
        }
        val playerClosed = player?.release() != false
        val micClosed = stopVoice()
        overlays.hideAll()
        return playerClosed && micClosed
    }

    /** Ends the current owner from this side and tells Dart (`owner.revoked`). */
    private fun revoke(reason: String) {
        val o = owner ?: return
        disposeLocal()
        emit(o, "owner.revoked", reason)
    }

    private fun emitCurrent(kind: String, reason: String? = null, localStopped: Boolean = false) {
        val o = owner ?: return
        emit(o, kind, reason, localStopped)
    }

    private fun emit(o: Owner, kind: String, reason: String? = null, localStopped: Boolean = false) {
        Log.i(TAG, "event $kind${reason?.let { " ($it)" } ?: ""}")
        sink?.success(
            buildMap {
                put("owner", o.token)
                put("generation", o.generation)
                put("lease", o.lease)
                put("kind", kind)
                if (reason != null) put("reason", reason)
                if (localStopped) put("localStopped", true)
            },
        )
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
        // No one left to hear about it: nothing may keep listening or playing.
        disposeLocal()
    }

    private companion object {
        const val TAG = "DshMedia"
        const val REQUEST_CODE = 0x4E4D
        // Frames waiting for the main thread, and frames Dart has not acknowledged.
        const val MIC_QUEUE = 8
        const val MIC_WINDOW = 16
        const val MIC_PER_TICK = 4
        const val MIC_FRAME_MS = 20L
        const val MAX_TITLE = 24
        const val MAX_CAPTION_CHARS = 240
        const val MAX_CAPTION_BYTES = 1024
    }
}
