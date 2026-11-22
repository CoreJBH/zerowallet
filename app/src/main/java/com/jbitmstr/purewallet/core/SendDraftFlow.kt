package com.jbitmstr.purewallet.core

/**
 * What the send screen does with the payment it has on it when the app leaves the screen,
 * kept free of Compose and Android so the rules can be tested on their own.
 *
 * The draft is one payment: a pasted invoice, a scanned QR, the amount and the route the
 * user picked. It must not be waiting on the screen for whoever picks the phone up next, so
 * leaving the screen drops it — except in the two cases where the user did not walk away from
 * it:
 *
 * - **A payment is already running.** Its send must not be cancelled, and the reference it
 *   ends with is what the user came back for.
 * - **The device-lock prompt is up** for this very payment. On Android the PIN / pattern
 *   screen is a system activity of its own, so the wallet's activity stops while the user is
 *   proving it is them — which looked exactly like the user leaving. Dropping the draft there
 *   threw away the request and amount the user had just reviewed, the one moment the sheet is
 *   the whole point. The draft is kept while the prompt is in charge instead.
 *
 * That second exception has an end: if the prompt finishes without the user (cancelled, the
 * system dismissed it, or the app was left open on the keyguard), the security rule wins over
 * the draft and it goes then. Only a prompt that ends in an authentication lets the payment
 * continue, and then the draft is spent anyway.
 */
internal class SendDraftFlow {

    /** A device-lock prompt for the payment on screen is up, or its result is on its way. */
    var authenticating: Boolean = false
        private set

    /** The screen was left while that prompt was up, so the draft only survived as a loan. */
    private var leftWhileAuthenticating: Boolean = false

    /** The user was asked to confirm it is them for this payment. */
    fun onAuthenticationStarted() {
        authenticating = true
        leftWhileAuthenticating = false
    }

    /**
     * The prompt reported its result. Returns true when the draft must be dropped now: the
     * prompt ended without the user after the screen had already been left.
     *
     * An authentication that failed while the sheet was still in front of the user is not a
     * reason to drop anything — the request and amount are right there for another try.
     */
    fun onAuthenticationFinished(granted: Boolean): Boolean {
        val drop = !granted && leftWhileAuthenticating
        authenticating = false
        leftWhileAuthenticating = false
        return drop
    }

    /**
     * The app left the screen. Returns true when the draft must be dropped now.
     *
     * [paymentInFlight] is a send on its way, or a reference the user is coming back to.
     */
    fun onBackgrounded(paymentInFlight: Boolean): Boolean {
        if (authenticating) {
            // The prompt is what took the screen. Keep the draft, and remember that it is
            // only here because of the prompt: if the prompt ends without the user, this
            // becomes a real leave and the draft goes then.
            leftWhileAuthenticating = true
            return false
        }
        return !paymentInFlight
    }
}
