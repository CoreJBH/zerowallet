package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.SensitiveClipboard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a copied wallet value back off the clipboard.
 *
 * The tenth review asked for the clear to work on API 26-32 as well, where Android has no
 * sensitive-clip handling of its own: a pasted address left on the clipboard is what a
 * clipboard-swapping app changes between the copy and the paste. The timer itself needs a
 * device, but whether the timer is allowed to clear is the rule worth pinning: it may only
 * take back this app's own copy.
 */
class SensitiveClipboardTest {

    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    @Test
    fun theCopyIsClearedWhileItIsStillTheOneThatWasMade() {
        assertTrue(SensitiveClipboard.shouldClear(address, address))
    }

    @Test
    fun somethingElseTheUserCopiedIsLeftAlone() {
        assertFalse(SensitiveClipboard.shouldClear("something I copied later", address))
    }

    @Test
    fun anUnreadableClipboardIsClearedRatherThanLeftCarryingOurValue() {
        // A backgrounded app is not allowed to read the clipboard. That is the state the
        // timer usually finds, and the value on it is either ours or one we cannot check.
        assertTrue(SensitiveClipboard.shouldClear(null, address))
    }

    @Test
    fun anEmptyClipboardIsClearedTooWhichCostsNothing() {
        assertTrue(SensitiveClipboard.shouldClear("", address))
    }

    @Test
    fun theDelayIsShortEnoughToBeUseful() {
        // Long enough to paste into another wallet by hand, short enough that the value is not
        // sitting on the clipboard while the phone is put down.
        assertTrue(SensitiveClipboard.CLEAR_DELAY_MS in 30_000L..300_000L)
    }
}
