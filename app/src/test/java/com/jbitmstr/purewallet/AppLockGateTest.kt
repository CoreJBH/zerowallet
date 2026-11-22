package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.AppLockGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open-lock gate across one session.
 *
 * Regression cover for `remember(openLockEnabled)`: keying the state on the option made every
 * change of it a new session, so turning lock-on-open on in Settings threw the user out to the
 * lock screen mid-sheet, and turning it off counted as an unlock that a later relock trusted.
 */
class AppLockGateTest {

    @Test
    fun aWalletWithTheOptionOnStartsClosed() {
        assertFalse(AppLockGate.afterStart(lockOnOpen = true))
    }

    @Test
    fun aWalletWithTheOptionOffStartsOpen() {
        assertTrue(AppLockGate.afterStart(lockOnOpen = false))
    }

    @Test
    fun turningTheOptionOnDoesNotThrowTheUserOut() {
        // The user is in the wallet and flips the switch in Settings: nothing about that
        // proves who is holding the phone, and nothing may close the gate under them.
        val open = AppLockGate.afterOptionChanged(open = true, enforced = true)
        assertTrue(open)
        // The next time the app leaves the screen it does ask.
        assertFalse(AppLockGate.afterLeavingScreen(open = open, enforced = true))
    }

    @Test
    fun turningTheOptionOffOpensTheGate() {
        assertTrue(AppLockGate.afterOptionChanged(open = false, enforced = false))
    }

    @Test
    fun aGateClosedByLeavingStaysClosedWhenTheOptionIsToggled() {
        val closed = AppLockGate.afterLeavingScreen(open = true, enforced = true)
        assertFalse(closed)
        // Toggling the option, or the phone's lock state changing, must not read as an
        // authentication that never happened.
        assertFalse(AppLockGate.afterOptionChanged(open = closed, enforced = true))
    }

    @Test
    fun leavingWithTheOptionOffKeepsTheWalletOpen() {
        assertTrue(AppLockGate.afterLeavingScreen(open = true, enforced = false))
    }

    @Test
    fun removingTheScreenLockOpensAGateThatWasWaiting() {
        // lockAvailable becomes false while the wallet sits behind the lock screen: the option
        // can no longer be asked for, so the gate must not keep the user out forever.
        assertTrue(AppLockGate.afterOptionChanged(open = false, enforced = false))
    }
}
