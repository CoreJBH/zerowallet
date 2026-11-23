package com.corejbh.zerowallet.core

/**
 * A request held for the next resume, which another request can arrive in front of.
 *
 * The app lock holds a prompt request while the screen is not resumed and starts it on the
 * next resume. Two requests can meet there - a send sheet and Settings can both ask - and only
 * one prompt can be shown. The one that is dropped has to be told, because a caller waiting
 * for an answer holds a busy button or a draft; replacing it silently is the same "waiting
 * forever for a prompt that is gone" bug the rest of this class exists to prevent.
 */
internal class HeldRequest<T> {

    private var held: T? = null

    /**
     * Holds [next], and hands any request already waiting to [abandon].
     *
     * @return true when an earlier request was displaced.
     */
    fun replace(next: T, abandon: (T) -> Unit): Boolean {
        val previous = held
        held = next
        if (previous != null) abandon(previous)
        return previous != null
    }

    /** Takes the held request for starting, or null when nothing was waiting. */
    fun take(): T? {
        val waiting = held
        held = null
        return waiting
    }

    /** Drops the held request, telling it through [abandon]; true when there was one. */
    fun clear(abandon: (T) -> Unit): Boolean {
        val waiting = held
        held = null
        if (waiting != null) abandon(waiting)
        return waiting != null
    }

    val isWaiting: Boolean get() = held != null
}

/**
 * Prompt-side state of the app lock, free of Android APIs so its transitions can be
 * tested without an Activity.
 *
 * The rule that matters: a prompt that never reports back — the system dismisses its own
 * biometric dialog when the app goes to the background — must not leave the lock believing
 * an attempt is still in flight. A stuck "busy" flag is what made the Unlock button do
 * nothing after the app came back from the background.
 *
 * The device-credential screen (PIN / pattern / password) is different: it is a system
 * activity, so the host activity stopping is exactly what it looks like while it is open,
 * and the attempt must survive it.
 */
class LockFlow {

    var inFlight: Boolean = false
        private set

    var showsSystemCredential: Boolean = false
        private set

    /** Starts a biometric attempt. A stale attempt is replaced, never waited on. */
    fun begin() {
        inFlight = true
        showsSystemCredential = false
    }

    /** The fallback screen is the system credential activity, not a dialog of ours. */
    fun beginSystemCredential() {
        inFlight = true
        showsSystemCredential = true
    }

    /** The system reported a result, a cancel, or an error: the attempt is over. */
    fun finish() {
        inFlight = false
        showsSystemCredential = false
    }

    /**
     * The host activity stopped. A biometric dialog is gone at that point (Android dismisses
     * it and may never call back), while a credential screen is the reason it stopped.
     * Returns true when the caller should cancel its prompt object as well.
     */
    fun onHostStopped(): Boolean {
        if (showsSystemCredential) return false
        if (!inFlight) return false
        finish()
        return true
    }

    /** The host activity is back in front; a new attempt may start. */
    fun canStart(): Boolean = !inFlight
}
