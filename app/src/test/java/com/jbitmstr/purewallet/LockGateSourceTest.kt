package com.jbitmstr.purewallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the open-lock gate takes its answer from, pinned by reading the code.
 *
 * The rule itself is plain Kotlin and lives in [com.jbitmstr.purewallet.core.AppLockGate],
 * tested there. What a JVM test cannot execute is the wiring: which value the screen gates on,
 * and whether the session knows the phone's answer before its first frame. Both are one line
 * each, and both are the difference between a lock screen and an open wallet.
 */
class LockGateSourceTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            listOf(File(dir, relative), File(dir, "app/$relative")).firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir?.parentFile
        }
        throw AssertionError("Could not find $relative from ${File("").absolutePath}")
    }

    private val walletApp = "src/main/java/com/jbitmstr/purewallet/ui/WalletApp.kt"
    private val viewModel = "src/main/java/com/jbitmstr/purewallet/WalletViewModel.kt"

    @Test
    fun theGateReadsTheAvailabilityTheSessionRefreshes() {
        val text = source(walletApp)
        assertTrue(
            "the gate follows the value re-read at every resume",
            text.contains("val openLockEnabled = state.lockOnOpen && lock.lockAvailable && lockArmed")
        )
        // The old line remembered the answer for the life of the Activity: removing the screen
        // lock while the app was away left the gate closed with no prompt able to open it.
        assertFalse(
            "no per-composition copy of the answer is left behind",
            text.contains("deviceLockAvailable")
        )
    }

    @Test
    fun theSessionKnowsTheAnswerBeforeTheFirstFrame() {
        assertTrue(
            "the wallet session reads the phone's answer when it is created",
            source(viewModel).contains("lockAvailable = AppLock.isAvailable(application)")
        )
    }
}
