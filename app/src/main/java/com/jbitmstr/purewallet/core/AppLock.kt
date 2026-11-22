package com.jbitmstr.purewallet.core

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * App lock in front of the wallet, off by default and enabled in Settings.
 *
 * On Android 11 and later one prompt accepts either a strong biometric or the device
 * screen lock (PIN, pattern, password). On older versions Android does not allow the two
 * in a single prompt, so the wallet asks for a strong biometric first and offers the
 * screen lock as the fallback button; phones with no strong biometric go straight to the
 * screen lock. Class 2 (weak) biometrics are never accepted on their own: on those
 * versions Android reports them as BIOMETRIC_WEAK, and a weak face unlock is not the proof
 * a wallet wants in front of spending.
 *
 * Failure is closed. When the phone has no screen lock, or the system reports that it
 * cannot authenticate right now, [authenticate] answers false instead of waving the gate
 * open; the caller shows a locked screen and the user can retry.
 *
 * No CryptoObject is bound to these prompts, on purpose: an auth-bound Keystore cipher
 * cannot be used together with the device-credential fallback on API 28-29 (the platform
 * rejects a CryptoObject for that authenticator), and on newer versions a key invalidated
 * by a screen-lock change would lock a working wallet out of its own UI. This gate guards
 * the screens, while the secret itself is already sealed by a Keystore key in
 * [SecureStore]; the phrase never travels through this class.
 *
 * Prompt state is lifecycle-driven, never a one-way flag. Every request replaces any
 * earlier attempt, and a request made while the activity is not resumed waits for the
 * next resume, so backgrounding the app can never leave Unlock unresponsive.
 *
 * Every request is answered exactly once. A prompt that the system dismisses without a
 * result, one this class supersedes, and one thrown away with the screen all report false
 * to their caller through [awaiting]; without that a sheet that is busy while it waits - the
 * send sheet is busy from the moment the prompt opens, on purpose - never heard back and
 * stayed busy with the prompt long gone.
 */
class AppLock(private val activity: FragmentActivity) {

    private val flow = LockFlow()
    private val executor = ContextCompat.getMainExecutor(activity)
    private var prompt: BiometricPrompt? = null
    private var attempt = 0
    private var pending: Pending? = null

    /** The caller of the prompt that is (or is about to be) on screen, until it is answered. */
    private val awaiting = PendingAuth()

    private data class Pending(
        val title: String,
        val subtitle: String,
        val onResult: (Boolean) -> Unit
    )

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> {
                // A biometric dialog is dismissed by the system here and may never call
                // back; a credential screen is still open and stays in charge.
                if (flow.onHostStopped()) supersede()
            }
            Lifecycle.Event.ON_RESUME -> {
                val queued = pending
                pending = null
                if (queued != null) start(queued.title, queued.subtitle, queued.onResult)
            }
            else -> Unit
        }
    }

    init {
        activity.lifecycle.addObserver(observer)
    }

    /**
     * True when the phone can authenticate right now, which needs a screen lock. Read it
     * again after every resume: a lock can be removed while the app is in the background.
     */
    fun isAvailable(): Boolean = isAvailable(activity)

    /** True while a prompt or the system credential screen is in charge. */
    fun isPrompting(): Boolean = flow.inFlight

    /**
     * Shows the prompt. [onResult] runs on the main thread with true only after the
     * system confirmed the user.
     *
     * When the phone cannot authenticate (no screen lock, or the system says it is not
     * available right now) the call reports false. The gate stays shut rather than opening
     * on an unproven claim: the caller keeps its locked screen and says what is missing.
     *
     * A request that arrives while the activity is not resumed (for example right after
     * the app returns from the background) is held until the next resume instead of being
     * dropped. Either way the request is answered exactly once: false when there is nothing
     * to ask with, false when another prompt owns the screen or this one is thrown away, and
     * the system's own answer when it arrives.
     */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        // The keyguard screen owns the attempt; a second prompt here would race it. The
        // caller is not left waiting for it either: the screen it owns is not this request's.
        if (flow.showsSystemCredential) {
            onResult(false)
            return
        }

        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pending = Pending(title, subtitle, onResult)
            return
        }
        start(title, subtitle, onResult)
    }

    /**
     * Stops observing and dismisses anything still on screen.
     *
     * A request held for the next resume is not going to get one, and a prompt still up is
     * about to lose its screen; both are answered false here rather than left waiting for a
     * result that can no longer arrive.
     */
    fun dispose() {
        activity.lifecycle.removeObserver(observer)
        val held = pending
        pending = null
        held?.onResult?.invoke(false)
        supersede()
    }

    private fun start(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        supersede()
        val token = attempt
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                show(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, title, subtitle, null, token, onResult)

            strongBiometricsEnrolled(activity) ->
                show(BIOMETRIC_STRONG, title, subtitle, FALLBACK_LABEL, token, onResult)

            else ->
                show(DEVICE_CREDENTIAL, title, subtitle, null, token, onResult)
        }
    }

    /**
     * Invalidates callbacks from the previous attempt and dismisses its prompt.
     *
     * The dismissed attempt is told it ended without the user. Its caller may be holding a
     * payment draft or a button that is busy until it hears back, and the system's own
     * callback for the cancelled prompt belongs to a token that is no longer current, so it
     * is dropped on the floor below and would never have reached the caller.
     */
    private fun supersede() {
        attempt++
        flow.finish()
        val active = prompt
        prompt = null
        runCatching { active?.cancelAuthentication() }
        awaiting.abandon()
    }

    private fun show(
        allowed: Int,
        title: String,
        subtitle: String,
        negative: String?,
        token: Int,
        onResult: (Boolean) -> Unit
    ) {
        if (allowed == DEVICE_CREDENTIAL) flow.beginSystemCredential() else flow.begin()
        awaiting.begin(onResult)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (token != attempt) return
                pending = null
                flow.finish()
                prompt = null
                awaiting.settle()
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (token != attempt) return
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && negative != null) {
                    // The user asked for the screen lock instead of a finger.
                    show(DEVICE_CREDENTIAL, title, subtitle, null, token, onResult)
                    return
                }
                pending = null
                flow.finish()
                prompt = null
                awaiting.settle()
                onResult(false)
            }

            override fun onAuthenticationFailed() {
                // A finger that does not match: the system prompt stays open for a retry.
            }
        }

        val active = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
        if (negative != null) {
            info.setNegativeButtonText(negative)
        }
        prompt = active
        try {
            active.authenticate(info.build())
        } catch (t: Throwable) {
            if (token == attempt) {
                pending = null
                flow.finish()
                prompt = null
                awaiting.settle()
                onResult(false)
            }
        }
    }

    companion object {
        private const val FALLBACK_LABEL = "Use screen lock"

        /** True when the phone has a screen lock and can authenticate the user. */
        fun isAvailable(context: Context): Boolean {
            if (!isDeviceSecure(context)) return false
            val manager = BiometricManager.from(context)
            val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            } else {
                DEVICE_CREDENTIAL
            }
            return manager.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
        }

        /** The activity that owns [context], for a prompt or a window flag. */
        fun activityOf(context: Context): FragmentActivity? {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is FragmentActivity) return current
                current = current.baseContext
            }
            return current as? FragmentActivity
        }

        private fun isDeviceSecure(context: Context): Boolean {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            return keyguard?.isDeviceSecure == true
        }

        /**
         * True only for a class 3 biometric. A weak (class 2) face or finger sensor is not
         * offered here: on API 26-29 it would be the whole proof, which is not enough for
         * a wallet. Those phones fall back to the device credential instead.
         */
        private fun strongBiometricsEnrolled(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}
