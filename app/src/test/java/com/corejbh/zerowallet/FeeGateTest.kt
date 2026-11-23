package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.FeeGate
import com.corejbh.zerowallet.core.FeeGateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send sheet's fee read, as a state that can end.
 *
 * The sheet used to have one nullable rate and no other state: a read that failed left Send
 * disabled with no button to press, so the only way out was closing the sheet. A failed read
 * now ends in [FeeGateStatus.FAILED], which is the one state that shows Retry, and Retry is
 * the only thing that starts another attempt.
 */
class FeeGateTest {

    @Test
    fun aFreshDraftHasNoRateAndNothingToRetry() {
        val gate = FeeGate()
        assertNull(gate.rateSatPerVb)
        assertEquals(FeeGateStatus.IDLE, gate.status)
        assertFalse(gate.showsRetry)
        assertFalse(gate.canSend)
    }

    @Test
    fun aReadInFlightHoldsTheSendButton() {
        val gate = FeeGate().reading()
        assertEquals(FeeGateStatus.READING, gate.status)
        assertFalse(gate.canSend)
        assertTrue(gate.waitsForRate)
        // Nothing has failed yet, so there is nothing to retry.
        assertFalse(gate.showsRetry)
    }

    @Test
    fun aRateThatArrivesUnlocksTheSend() {
        val gate = FeeGate().reading().resolved(12L)
        assertEquals(FeeGateStatus.READY, gate.status)
        assertEquals(12L, gate.rateSatPerVb)
        assertTrue(gate.canSend)
        assertFalse(gate.waitsForRate)
        assertFalse(gate.showsRetry)
    }

    @Test
    fun aReadThatComesBackEmptyFailsInsteadOfWaitingForever() {
        val gate = FeeGate().reading().resolved(null)
        assertEquals(FeeGateStatus.FAILED, gate.status)
        assertNull(gate.rateSatPerVb)
        assertTrue(gate.showsRetry)
        assertTrue(gate.waitsForRate)
        assertFalse(gate.canSend)
    }

    @Test
    fun retryStartsAnotherAttemptAndClearsTheFailure() {
        val failed = FeeGate().reading().resolved(null)
        val retried = failed.retry()
        assertEquals(FeeGateStatus.READING, retried.status)
        assertEquals(1, retried.attempts)
        // The screen keys its read on the attempt count, which is what makes Retry a new call.
        assertTrue(retried.attempts != failed.attempts)
        assertFalse(retried.showsRetry)
    }

    @Test
    fun aRetryThatWorksUnlocksTheSend() {
        val gate = FeeGate().reading().resolved(null).retry().resolved(9L)
        assertTrue(gate.canSend)
        assertEquals(9L, gate.rateSatPerVb)
        assertEquals(1, gate.attempts)
    }

    @Test
    fun retryDoesNothingWhileAReadIsRunning() {
        // A second tap on Retry must not start a third attempt under the one already running.
        val reading = FeeGate().reading().resolved(null).retry()
        assertEquals(reading, reading.retry())
    }

    @Test
    fun aNewAttemptDropsThePreviousRate() {
        // Switching Normal/Fast must not keep showing the other priority's rate for a moment.
        val ready = FeeGate().reading().resolved(30L)
        assertNull(ready.reading().rateSatPerVb)
        assertFalse(ready.reading().canSend)
    }
}
