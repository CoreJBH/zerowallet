package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.HeldRequest
import com.corejbh.zerowallet.core.LockFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock-state transitions across lifecycle events. Regression cover for the bug where a
 * biometric prompt dismissed by the system on backgrounding left the lock busy forever,
 * so the Unlock button stopped responding.
 */
class LockFlowTest {

    @Test
    fun aStartedAttemptIsInFlight() {
        val flow = LockFlow()
        flow.begin()
        assertTrue(flow.inFlight)
        assertFalse(flow.showsSystemCredential)
    }

    @Test
    fun finishingAnAttemptClearsIt() {
        val flow = LockFlow()
        flow.begin()
        flow.finish()
        assertFalse(flow.inFlight)
        assertTrue(flow.canStart())
    }

    @Test
    fun backgroundingDuringBiometricsResetsTheAttempt() {
        val flow = LockFlow()
        flow.begin()
        // The system dismisses the dialog when the app leaves the foreground.
        assertTrue(flow.onHostStopped())
        assertFalse(flow.inFlight)
        assertTrue("the next tap must be able to start again", flow.canStart())
    }

    @Test
    fun backgroundingWithoutAnAttemptIsHarmless() {
        val flow = LockFlow()
        assertFalse(flow.onHostStopped())
        assertFalse(flow.inFlight)
    }

    @Test
    fun theCredentialScreenSurvivesTheHostStopping() {
        val flow = LockFlow()
        flow.begin()
        flow.beginSystemCredential()
        assertTrue(flow.showsSystemCredential)
        // The keyguard activity is why our activity stopped: keep the attempt.
        assertFalse(flow.onHostStopped())
        assertTrue(flow.inFlight)
    }

    @Test
    fun returningFromTheCredentialScreenFinishesIt() {
        val flow = LockFlow()
        flow.beginSystemCredential()
        flow.onHostStopped()
        flow.finish()
        assertFalse(flow.inFlight)
        assertTrue(flow.canStart())
    }

    @Test
    fun aNewAttemptAlwaysStartsAfterAStaleOne() {
        val flow = LockFlow()
        flow.begin()
        flow.onHostStopped()
        // Tapping Unlock again: no waiting on the dead attempt.
        flow.begin()
        assertTrue(flow.inFlight)
    }

    @Test
    fun fallingBackToTheCredentialScreenKeepsOneAttempt() {
        val flow = LockFlow()
        flow.begin()
        flow.beginSystemCredential()
        assertTrue(flow.inFlight)
        assertTrue(flow.showsSystemCredential)
        // A host stop between the two prompts must not cancel the credential screen.
        assertFalse(flow.onHostStopped())
    }

    // -------------------------------------------------- a request held for the next resume

    /** One held request: what it was asked for, and whether it has been answered. */
    private class Asked(val reason: String) {
        var answered: Boolean? = null
    }

    @Test
    fun aHeldRequestIsAnsweredWhenAnotherOneArrivesInFrontOfIt() {
        // The tenth review: a second request used to overwrite the first, whose caller stayed
        // busy waiting for a prompt that would never open.
        val first = Asked("send")
        val second = Asked("settings")
        val held = HeldRequest<Asked>()

        assertFalse(held.replace(first) { it.answered = false })
        assertTrue(held.isWaiting)
        assertTrue("the displaced request is told", held.replace(second) { it.answered = false })
        assertEquals(false, first.answered)
        assertNull("and the new one is still waiting for its turn", second.answered)
    }

    @Test
    fun theRequestThatReachesTheResumeIsTheOneThatStarts() {
        val first = Asked("send")
        val held = HeldRequest<Asked>()
        held.replace(first) { it.answered = false }

        assertEquals(first, held.take())
        assertFalse(held.isWaiting)
        // Taking it answers nothing: it is about to be asked.
        assertNull(first.answered)
        assertNull(held.take())
    }

    @Test
    fun disposingAnswersTheHeldRequestRatherThanDroppingIt() {
        val held = Asked("send")
        val queue = HeldRequest<Asked>()
        queue.replace(held) { it.answered = false }

        assertTrue(queue.clear { it.answered = false })
        assertEquals(false, held.answered)
        assertFalse(queue.clear { it.answered = false })
    }

    @Test
    fun nothingHeldIsNotAnAnswer() {
        val queue = HeldRequest<Asked>()
        assertFalse(queue.isWaiting)
        assertNull(queue.take())
        assertFalse(queue.replace(Asked("send")) { })
    }

    @Test
    fun aHeldRequestFromAnEarlierRoundIsAnsweredOnce() {
        val first = Asked("send")
        val second = Asked("settings")
        val queue = HeldRequest<Asked>()
        queue.replace(first) { it.answered = false }
        queue.replace(second) { it.answered = false }
        queue.clear { it.answered = false }

        assertEquals(false, first.answered)
        assertEquals(false, second.answered)
    }
}
