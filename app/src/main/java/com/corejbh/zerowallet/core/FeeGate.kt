package com.corejbh.zerowallet.core

/**
 * What the send screen knows about the on-chain fee rate while it reads one.
 *
 * The rate is read from a chain endpoint, and until it arrives the send button has to stay
 * shut: without a rate the high-fee check cannot be asked, and the payment would go out at a
 * number nobody looked at (see [FeeWarning.isWaitingForRate]). But "waiting" was also the
 * only state the screen had. An endpoint that was down, a phone that had no data, or a reply
 * that did not parse left the button disabled for as long as the screen stayed open, with no
 * way to ask again: the only escape was backing out of the sheet and starting over.
 *
 * So the wait is a state with an end. [READING] holds the button while an attempt is running,
 * [READY] carries the rate the payment will be signed with, and [FAILED] says the attempt
 * ended without one - which is exactly when a Retry is offered, and the only thing that
 * starts another attempt.
 */
enum class FeeGateStatus {
    /** No on-chain send is on screen, so there is nothing to read. */
    IDLE,

    /** An attempt is running; the send button waits for it. */
    READING,

    /** A rate arrived. This is the rate the payment is signed with. */
    READY,

    /** The attempt ended without a rate. Send stays shut and Retry is shown. */
    FAILED
}

/**
 * The fee-read state of one send draft.
 *
 * Immutable and free of Android so the transitions the screen depends on are unit-tested:
 * a failed attempt shows Retry, Retry reads again instead of leaving the failure on screen,
 * and a send is only allowed with a rate that actually arrived.
 */
data class FeeGate(
    val status: FeeGateStatus = FeeGateStatus.IDLE,
    val rateSatPerVb: Long? = null,
    /** How many attempts this draft has made; the screen keys its read on it. */
    val attempts: Int = 0
) {

    /** An attempt begins: whatever rate the last one produced is gone. */
    fun reading(): FeeGate = copy(status = FeeGateStatus.READING, rateSatPerVb = null)

    /** An attempt ended, with a rate or without one. */
    fun resolved(rateSatPerVb: Long?): FeeGate = if (rateSatPerVb == null) {
        copy(status = FeeGateStatus.FAILED, rateSatPerVb = null)
    } else {
        copy(status = FeeGateStatus.READY, rateSatPerVb = rateSatPerVb)
    }

    /** Retry is only offered after a failed attempt, and it starts the next one. */
    fun retry(): FeeGate = if (status == FeeGateStatus.FAILED) {
        copy(status = FeeGateStatus.READING, rateSatPerVb = null, attempts = attempts + 1)
    } else {
        this
    }

    /** True while the send button must stay shut: reading, or no rate at all. */
    val waitsForRate: Boolean
        get() = status == FeeGateStatus.READING || status == FeeGateStatus.FAILED

    /** True only when a Retry button belongs on screen. */
    val showsRetry: Boolean
        get() = status == FeeGateStatus.FAILED

    /** True when a send may be signed at [rateSatPerVb]. */
    val canSend: Boolean
        get() = status == FeeGateStatus.READY && rateSatPerVb != null
}
