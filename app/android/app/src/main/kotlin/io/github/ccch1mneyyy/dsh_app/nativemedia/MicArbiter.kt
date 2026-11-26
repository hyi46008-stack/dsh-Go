package io.github.ccch1mneyyy.dsh_app.nativemedia

/**
 * One microphone in the app: at most one *session* at a time, either a plugin's listening or a single
 * dictation. A plugin's recorder that is still closing keeps its claim; dictation is then refused
 * (null token) instead of opening a second recorder, and a refused request never locks dictation out.
 *
 * Every state transition is synchronized: the callers are the main thread and tests, but a second
 * thread calling in must never hand out two tokens.
 */
object MicArbiter {
    /**
     * A plugin's listening. [yieldMic] stops it and reports whether the recorder is already closed:
     * true = the microphone is free, false = STILL CLOSING (the holder keeps its claim).
     */
    fun interface Holder {
        fun yieldMic(): Boolean
    }

    private var plugin: Holder? = null
    // A holder whose recorder has not exited yet. It keeps the microphone until it releases.
    private var closing: Holder? = null
    private var issued = 0
    // The one dictation session that may hold a recorder; null when idle.
    private var current: Int? = null

    /** A dictation is running (or still closing). */
    @Volatile
    var dictating = false
        private set

    /**
     * Dictation starts: returns its token, or null when a dictation is already running, a plugin's
     * recorder is still closing, or the plugin could not stop. Nothing is given up on refusal; a
     * later call retries the yield, so a refusal never locks dictation out.
     */
    @Synchronized
    fun beginDictation(): Int? {
        if (dictating) return null
        val holder = plugin
        if (holder == null) {
            // A close from an older session that has no holder left: never grant over it.
            if (closing != null) return null
            return nextToken()
        }
        // Claim the session before yielding, so no other source can slip in while it stops.
        dictating = true
        val stopped = runCatching { holder.yieldMic() }.getOrDefault(false)
        if (!stopped) {
            closing = holder
            dictating = false
            return null
        }
        if (plugin === holder) plugin = null
        closing = null
        return nextToken()
    }

    /** Frees the session, but only for the token that owns it. Unknown/old tokens do nothing. */
    @Synchronized
    fun endDictation(token: Int) {
        if (current != token) return
        current = null
        dictating = false
    }

    /** A plugin may listen: false while dictation runs, while another holder is active, or closing. */
    @Synchronized
    fun acquire(holder: Holder): Boolean {
        if (dictating || closing != null || plugin != null) return false
        plugin = holder
        return true
    }

    /** The holder is done with the microphone; a closing holder frees the claim here. */
    @Synchronized
    fun release(holder: Holder) {
        if (plugin === holder) plugin = null
        if (closing === holder) closing = null
    }

    private fun nextToken(): Int {
        val token = ++issued
        current = token
        dictating = true
        return token
    }

    /** Tests only. */
    @Synchronized
    internal fun reset() {
        plugin = null
        closing = null
        current = null
        issued = 0
        dictating = false
    }
}
