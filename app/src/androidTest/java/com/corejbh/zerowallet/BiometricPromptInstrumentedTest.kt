package com.corejbh.zerowallet

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.corejbh.zerowallet.core.AppLock
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the device lock can actually do on this phone, against the real biometric stack.
 *
 * The wallet's rule is that a privileged action runs only on a reported success from
 * `BiometricPrompt`, and that a phone with no screen lock can report nothing. Both halves start
 * with `BiometricManager`, which is what this test pins: the answers the app maps are the ones
 * the platform gives, and asking for an activity from an application context yields none (a
 * prompt must hang off the wallet window, not off the process).
 *
 * The prompt itself is not answered here - a test cannot press a finger - but its precondition
 * is, which is the state every screen checks before it shows one.
 */
@RunWith(AndroidJUnit4::class)
class BiometricPromptInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theDeviceLockAnswersWithAKnownValue() {
        val manager = BiometricManager.from(context)
        val answer = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        assertTrue(
            "unknown biometric answer: $answer",
            answer in setOf(
                BiometricManager.BIOMETRIC_SUCCESS,
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN
            )
        )
    }

    @Test
    fun appLockAvailabilityMatchesThePhonesOwnScreenLock() {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val manager = BiometricManager.from(context)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val stackSays = manager.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

        // The app never claims more than the phone has: without a device credential there is
        // nothing to prove it is the user, so no privileged action may run.
        if (!keyguard.isDeviceSecure) {
            assertTrue("no screen lock, but the app says it can authenticate", !AppLock.isAvailable(context))
        } else {
            assertTrue(
                "with a screen lock the app's answer is the biometric stack's answer",
                AppLock.isAvailable(context) == stackSays
            )
        }
    }

    @Test
    fun aPromptNeedsAnActivityNotAnApplicationContext() {
        // BiometricPrompt hangs off the fragment manager of the wallet window. Handing it the
        // process context is what makes a prompt silently never appear, so the lookup that
        // returns it has to be honest about an application context having none.
        assertNull(AppLock.activityOf(context))
    }
}
