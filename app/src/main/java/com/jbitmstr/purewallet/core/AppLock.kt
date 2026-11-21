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

/**
 * App lock in front of the wallet.
 *
 * On Android 11 and later one prompt accepts either a strong biometric or the device
 * screen lock (PIN, pattern, password). On older versions Android does not allow the two
 * in a single prompt, so the wallet asks for a fingerprint first and offers the screen
 * lock as the fallback button; phones with no biometrics go straight to the screen lock.
 *
 * Nothing here holds key material: this is a gate in front of the UI. The seed itself
 * stays encrypted by an Android Keystore key in [SecureStore].
 */
class AppLock(private val activity: FragmentActivity) {

    private var prompt: BiometricPrompt? = null
    private var running = false

    /** True while a prompt is on screen or the system credential screen is open. */
    fun isRunning(): Boolean = running

    /** True when the phone can authenticate at all, which needs a screen lock. */
    fun isAvailable(): Boolean = isAvailable(activity)

    /**
     * Shows the prompt. [onResult] runs on the main thread with true only after the
     * system confirmed the user. When the phone has no screen lock the app cannot ask for
     * anything, so the call reports success and the caller stays usable.
     */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(true)
            return
        }
        if (running) return
        running = true

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                show(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, title, subtitle, null, onResult)

            biometricsEnrolled(activity) ->
                show(BIOMETRIC_WEAK, title, subtitle, FALLBACK_LABEL, onResult)

            else ->
                show(DEVICE_CREDENTIAL, title, subtitle, null, onResult)
        }
    }

    /** Dismisses a prompt that is still waiting. */
    fun cancel() {
        running = false
        val active = prompt
        prompt = null
        runCatching { active?.cancelAuthentication() }
    }

    private fun show(
        allowed: Int,
        title: String,
        subtitle: String,
        negative: String?,
        onResult: (Boolean) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                running = false
                prompt = null
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && negative != null) {
                    // The user asked for the screen lock instead of a finger.
                    show(DEVICE_CREDENTIAL, title, subtitle, null, onResult)
                    return
                }
                running = false
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
            running = false
            prompt = null
            onResult(false)
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
