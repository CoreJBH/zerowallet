package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.SendDraftFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the send sheet's draft goes and when it stays. The regression this covers: on Android
 * the screen-lock screen (PIN / pattern) is a system activity, so authenticating a payment
 * stopped the wallet's activity, and the sheet read that exactly like the user leaving and
 * cleared the request and amount they had just reviewed.
 */
class SendDraftFlowTest {

    @Test
    fun leavingWithNothingInFlightDropsTheDraft() {
        val flow = SendDraftFlow()
        assertTrue(flow.onBackgrounded(paymentInFlight = false))
    }

    @Test
    fun aRunningPaymentKeepsTheDraft() {
        val flow = SendDraftFlow()
        assertFalse(flow.onBackgrounded(paymentInFlight = true))
    }

    @Test
    fun theLockPromptKeepsTheDraft() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        assertTrue(flow.authenticating)
        // The keyguard activity is why the wallet's activity stopped.
        assertFalse("the prompt is not the user leaving", flow.onBackgrounded(paymentInFlight = false))
    }

    @Test
    fun authenticatingInFrontOfTheUserDropsNothing() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        // A fingerprint was not recognised and the prompt closed: the reviewed payment is
        // still on the screen behind it, so nothing is thrown away.
        assertFalse(flow.onAuthenticationFinished(granted = false))
        assertFalse(flow.authenticating)
        // The draft is still here: only a real leave takes it.
        assertTrue(flow.onBackgrounded(paymentInFlight = false))
    }

    @Test
    fun aCancelledPromptAfterLeavingDropsTheDraft() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        flow.onBackgrounded(paymentInFlight = false)
        // The user put the phone down and the prompt ended without them: the draft must not
        // be the first thing the next person sees.
        assertTrue(flow.onAuthenticationFinished(granted = false))
    }

    @Test
    fun anAuthenticatedPaymentDoesNotDropTheDraftUnderItself() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        flow.onBackgrounded(paymentInFlight = false)
        // The user came back and proved it is them: the payment goes out and the sheet shows
        // its reference, so there is no draft left to clear.
        assertFalse(flow.onAuthenticationFinished(granted = true))
        assertFalse(flow.authenticating)
    }

    @Test
    fun aSecondPromptIsNotHauntedByTheFirst() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        flow.onBackgrounded(paymentInFlight = false)
        flow.onAuthenticationFinished(granted = false)
        // Asking again, then failing again while the sheet is in front: no stale "left" flag
        // turns the second failure into a cleared draft.
        flow.onAuthenticationStarted()
        assertFalse(flow.onAuthenticationFinished(granted = false))
    }

    @Test
    fun theLeaveAfterAPromptStillDropsTheDraft() {
        val flow = SendDraftFlow()
        flow.onAuthenticationStarted()
        flow.onBackgrounded(paymentInFlight = false)
        flow.onAuthenticationFinished(granted = true)
        // Authenticated, sent, and now the user really leaves: nothing to keep, and the next
        // leave is a plain one.
        assertTrue(flow.onBackgrounded(paymentInFlight = false))
    }
}
