package com.jbitmstr.purewallet.core

/**
 * The callback of a prompt that is on screen, so it is never left waiting.
 *
 * Android dismisses its own biometric dialog when the app goes to the background and may
 * never call back, and a prompt this app cancels answers through a token that is already
 * stale. Both are "no answer", and a caller holding a draft or a busy button cannot tell
 * them apart from "still waiting" - which is how the send sheet ended up stuck with
 * `busy = true` and a prompt that was long gone, so its button did nothing.
 *
 * This keeps the one callback that is still owed an answer, settles it when the system does
 * answer, and abandons it - with a no - when the prompt goes away without one. Each attempt
 * is answered at most once: the first of [settle] and [abandon] wins, and a replaced or
 * cancelled attempt can never answer twice.
 */
internal class PendingAuth {

    private var callback: ((Boolean) -> Unit)? = null

    /** A prompt for [onResult] is now on screen. Any earlier attempt is taken as over. */
    fun begin(onResult: (Boolean) -> Unit) {
        callback = onResult
    }

    /** The system answered, so its caller has been told and nothing is owed any more. */
    fun settle() {
        callback = null
    }

    /**
     * The prompt is gone without an answer (dismissed with the app, cancelled by us, or the
     * screen it belonged to is gone). Reports false to its caller and returns true when
     * there was one to report to.
     */
    fun abandon(): Boolean {
        val owed = callback ?: return false
        callback = null
        owed(false)
        return true
    }

    /** Whether an attempt is still waiting for an answer. */
    val isWaiting: Boolean get() = callback != null
}
