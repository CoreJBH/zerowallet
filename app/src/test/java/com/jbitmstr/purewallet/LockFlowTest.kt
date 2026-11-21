package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.LockFlow
import org.junit.Assert.assertFalse
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
}
