package com.jbitmstr.purewallet.core

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
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
 * in a single prompt, so the wallet asks for a fingerprint first and offers the screen
 * lock as the fallback button; phones with no biometrics go straight to the screen lock.
 *
 * Nothing here holds key material: this is a gate in front of the UI. The seed itself
 * stays encrypted by an Android Keystore key in [SecureStore].
 *
 * Prompt state is lifecycle-driven, never a one-way flag. Every request replaces any
 * earlier attempt, and a request made while the activity is not resumed waits for the
 * next resume, so backgrounding the app can never leave Unlock unresponsive.
 */
class AppLock(private val activity: FragmentActivity) {

    private val flow = LockFlow()
    private val executor = ContextCompat.getMainExecutor(activity)
    private var prompt: BiometricPrompt? = null
    private var attempt = 0
    private var pending: Pending? = null

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

    /** True when the phone can authenticate at all, which needs a screen lock. */
    fun isAvailable(): Boolean = isAvailable(activity)

    /** True while a prompt or the system credential screen is in charge. */
    fun isPrompting(): Boolean = flow.inFlight

    /**
     * Shows the prompt. [onResult] runs on the main thread with true only after the
     * system confirmed the user. When the phone has no screen lock the app cannot ask for
     * anything, so the call reports success and the caller stays usable.
     *
     * A request that arrives while the activity is not resumed (for example right after
     * the app returns from the background) is held until the next resume instead of being
     * dropped.
     */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(true)
            return
        }
        // The keyguard screen owns the attempt; a second prompt here would race it.
        if (flow.showsSystemCredential) return

        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pending = Pending(title, subtitle, onResult)
            return
        }
        start(title, subtitle, onResult)
    }

    /** Stops observing and dismisses anything still on screen. */
    fun dispose() {
        activity.lifecycle.removeObserver(observer)
        pending = null
        supersede()
    }

    private fun start(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        supersede()
        val token = attempt
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                show(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, title, subtitle, null, token, onResult)

            biometricsEnrolled(activity) ->
                show(BIOMETRIC_WEAK, title, subtitle, FALLBACK_LABEL, token, onResult)

            else ->
                show(DEVICE_CREDENTIAL, title, subtitle, null, token, onResult)
        }
    }

    /** Invalidates callbacks from the previous attempt and dismisses its prompt. */
    private fun supersede() {
        attempt++
        flow.finish()
        val active = prompt
        prompt = null
        runCatching { active?.cancelAuthentication() }
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

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (token != attempt) return
                pending = null
                flow.finish()
                prompt = null
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

        private fun biometricsEnrolled(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}
