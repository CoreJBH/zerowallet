package com.corejbh.zerowallet.core

/**
 * What a screen may ask the device lock for: one way to run the thing behind it.
 *
 * Every privileged action - sending a payment, funding or closing a channel, showing the
 * recovery phrase, saving or restoring a channel backup, wiping the wallet - goes through
 * [guarded], and only through it. The prompt's answer never reaches the body of the action:
 * a reported success runs [action], and a cancelled prompt, a dismissed prompt or a phone
 * with no screen lock runs [onDenied] instead of it. That is the point of the type. A call
 * site cannot accidentally keep the body of the action where the answer is ignored, because
 * the answer is not handed to that body at all. That is how the prompt used to be bypassed:
 * a sheet took the prompt's callback as a parameter, wrote the privileged call into the
 * lambda body and never looked at the boolean it was handed, so cancelling the prompt, or a
 * phone with no screen lock at all, still ran the action.
 *
 * The prompt itself is supplied by the app, because it needs an Activity and a
 * BiometricPrompt; this class stays free of Android so the rule can be tested on its own.
 * [request] must answer exactly once - [com.corejbh.zerowallet.core.AppLock] guarantees
 * that, including for a prompt that the system dismisses without a result.
 */class Authorization(private val request: (String, (Boolean) -> Unit) -> Unit) {

    /**
     * Asks for the proof described by [reason] and runs [action] only when it was given.
     *
     * [onDenied] runs instead of [action] when the user did not give it - cancelled, backed
     * out, or no device lock to ask at all. The two never run together, and neither runs
     * while the prompt is still on screen.
     */
    fun guarded(reason: String, onDenied: () -> Unit = {}, action: () -> Unit) {
        request(reason) { granted ->
            if (granted) action() else onDenied()
        }
    }

    companion object {
        /**
         * An authorization that approves nothing, for a screen with no device lock behind it.
         * Its [guarded] body is never run.
         */
        val NONE: Authorization = Authorization { _, onResult -> onResult(false) }
    }
}
