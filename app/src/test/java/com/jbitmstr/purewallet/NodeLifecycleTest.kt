package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.DEFAULT_LISTENING_ADDRESS
import com.jbitmstr.purewallet.core.NodeLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Start, stop and retry rules for the on-device node.
 *
 * Regression cover for the bug where the node sat on "Stopped" with no explanation: a start
 * that failed was not shown, a second start could be dropped while the first was running,
 * and a node the system froze in the background was never brought back.
 */
class NodeLifecycleTest {

    // ------------------------------------------------------- coming back to the foreground

    @Test
    fun comingBackStartsANodeThatIsDown() {
        val lifecycle = NodeLifecycle()
        assertTrue(
            lifecycle.shouldStartOnForeground(seedPresent = true, running = false, starting = false)
        )
    }

    @Test
    fun comingBackLeavesARunningNodeAlone() {
        val lifecycle = NodeLifecycle()
        assertFalse(
            lifecycle.shouldStartOnForeground(seedPresent = true, running = true, starting = false)
        )
    }

    @Test
    fun comingBackDoesNotStartASecondNode() {
        val lifecycle = NodeLifecycle()
        assertFalse(
            "a node already on its way must not be started again",
            lifecycle.shouldStartOnForeground(seedPresent = true, running = false, starting = true)
        )
    }

    @Test
    fun comingBackNeverStartsWithoutAPhrase() {
        val lifecycle = NodeLifecycle()
        assertFalse(
            lifecycle.shouldStartOnForeground(seedPresent = false, running = false, starting = false)
        )
    }

    // ------------------------------------------------------------ manually stopped

    @Test
    fun aNodeTheUserStoppedStaysDown() {
        val lifecycle = NodeLifecycle()
        lifecycle.onUserStop()
        assertTrue(lifecycle.manuallyStopped)
        assertFalse(
            lifecycle.shouldStartOnForeground(seedPresent = true, running = false, starting = false)
        )
    }

    @Test
    fun anExplicitStartClearsTheManualStop() {
        val lifecycle = NodeLifecycle()
        lifecycle.onUserStop()
        lifecycle.onUserStart()
        assertFalse(lifecycle.manuallyStopped)
        assertTrue(
            lifecycle.shouldStartOnForeground(seedPresent = true, running = false, starting = false)
        )
    }

    @Test
    fun aManualStopSurvivesAFailedStart() {
        // Failing to come back must not turn into a stream of automatic retries the user
        // did not ask for; only an explicit start resets the choice.
        val lifecycle = NodeLifecycle()
        lifecycle.onUserStop()
        lifecycle.onUserStop()
        assertTrue(lifecycle.manuallyStopped)
        assertFalse(
            lifecycle.shouldStartOnForeground(seedPresent = true, running = false, starting = false)
        )
    }

    // ------------------------------------------------------------- port 9735 fallback

    @Test
    fun theFirstAttemptListensOnTheLightningPort() {
        val attempts = NodeLifecycle().startAttempts()
        assertEquals(DEFAULT_LISTENING_ADDRESS, attempts.first())
        assertEquals(2, attempts.size)
    }

    @Test
    fun theSecondAttemptRunsWithoutAListeningAddress() {
        val attempts = NodeLifecycle().startAttempts()
        assertNull("the fallback must not try to bind the port again", attempts[1])
    }

    @Test
    fun aFailureOnThePortIsWorthRetrying() {
        val lifecycle = NodeLifecycle()
        assertTrue(lifecycle.shouldRetryWithoutListening(DEFAULT_LISTENING_ADDRESS))
    }

    @Test
    fun aFailureWithoutThePortIsNotRetried() {
        val lifecycle = NodeLifecycle()
        assertFalse("a start without a listening address has nothing to fall back to", lifecycle.shouldRetryWithoutListening(null))
    }

    @Test
    fun aWalletWithNoListeningAddressStartsOnce() {
        val lifecycle = NodeLifecycle(listeningAddress = null)
        assertEquals(listOf<String?>(null), lifecycle.startAttempts())
    }

    @Test
    fun aBlankListeningAddressIsTreatedAsNone() {
        val lifecycle = NodeLifecycle(listeningAddress = "  ")
        assertEquals(listOf<String?>(null), lifecycle.startAttempts())
        assertFalse(lifecycle.shouldRetryWithoutListening("  "))
    }
}
