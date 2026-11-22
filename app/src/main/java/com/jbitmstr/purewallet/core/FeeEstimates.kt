package com.jbitmstr.purewallet.core

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Reads the standard Esplora `/fee-estimates` body.
 *
 * The reply is a JSON object whose keys are confirmation targets in blocks and whose values
 * are fee rates in sat/vB, for example
 * `{"1": 1.657, "3": 1.109, "6": 0.137, "1008": 0.108}`. Blockstream serves exactly this,
 * while mempool.space additionally offers the numbered `/v1/fees/recommended` helper; this
 * parser keeps the wallet working on either.
 *
 * The body is parsed as JSON, not scanned with a regular expression: a string elsewhere in
 * a hostile reply (`{"note":"\"1\": 999999"}`) must not be able to become a fee rate, and a
 * value that is not a number must be skipped rather than guessed at. Every rate is then
 * clamped to [MIN_SAT_PER_VB]..[MAX_SAT_PER_VB]: the floor every node accepts, and a ceiling
 * no sane mempool reaches, so a broken or hostile endpoint cannot push a send into paying a
 * thousand times the going rate.
 */
object FeeEstimates {

    /** The floor every node accepts, and the smallest rate this wallet will send with. */
    const val MIN_SAT_PER_VB = 1L

    /**
     * The highest rate this wallet will send with (1000 sat/vB).
     *
     * A block is 1M weight units ≈ 250k vB, so this is far above any rate a real mempool has
     * reached. A reply above it is treated as broken rather than as an instruction to overpay.
     */
    const val MAX_SAT_PER_VB = 1000L

    fun fromEsplora(body: String): FeeRates? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val byTarget = LinkedHashMap<Int, Double>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val target = key.toIntOrNull() ?: continue
            // optDouble coerces a quoted number ("1.5") and gives NaN for anything else.
            val rate = json.optDouble(key, Double.NaN)
            if (rate.isFinite() && rate > 0.0) byTarget[target] = rate
        }
        if (byTarget.isEmpty()) return null

        // The closest target at or after the wanted one; the farthest estimate when the
        // node stops earlier than that (a slow weekend can leave no entry for a target).
        fun rateFor(target: Int): Long {
            val key = byTarget.keys.filter { it >= target }.minOrNull() ?: byTarget.keys.max()
            return satPerVb(byTarget.getValue(key))
        }

        return FeeRates(
            fastestSatPerVb = rateFor(1),
            halfHourSatPerVb = rateFor(3),
            hourSatPerVb = rateFor(6),
            minimumSatPerVb = satPerVb(byTarget.values.min())
        )
    }

    /**
     * A fractional sat/vB rate as a whole number inside the allowed range.
     *
     * A NaN or a negative rate carries no usable information and becomes the floor; an
     * infinite one is the endpoint asking for the maximum, which is the ceiling.
     */
    fun satPerVb(rate: Double): Long = when {
        rate.isNaN() || rate == Double.NEGATIVE_INFINITY -> MIN_SAT_PER_VB
        rate == Double.POSITIVE_INFINITY -> MAX_SAT_PER_VB
        else -> clamp(ceil(rate).toLong())
    }

    /** Keeps any rate this wallet uses inside [MIN_SAT_PER_VB]..[MAX_SAT_PER_VB]. */
    fun clamp(satPerVb: Long): Long = satPerVb.coerceIn(MIN_SAT_PER_VB, MAX_SAT_PER_VB)
}

/**
 * What an on-chain send is likely to cost.
 *
 * The wallet cannot know the transaction before the node builds it (the number of inputs
 * depends on which coins it picks), so the confirmation screen shows an estimate for the
 * usual shape of a payment: one P2WPKH input and one P2WPKH change output. A P2WPKH input
 * weighs 68 vB, an output 31 vB, and the version, locktime and segwit marker add 11 vB.
 * The node still computes the real fee from the transaction it signs.
 */
object OnchainFeeEstimate {

    const val TX_OVERHEAD_VBYTES = 11L
    const val P2WPKH_INPUT_VBYTES = 68L
    const val P2WPKH_OUTPUT_VBYTES = 31L

    /** Virtual size of a transaction with [inputs] P2WPKH inputs and [outputs] P2WPKH outputs. */
    fun vBytes(inputs: Int, outputs: Int): Long =
        TX_OVERHEAD_VBYTES +
            P2WPKH_INPUT_VBYTES * inputs.coerceAtLeast(1) +
            P2WPKH_OUTPUT_VBYTES * outputs.coerceAtLeast(1)

    /** The estimated fee in sats for a transaction of that shape. */
    fun feeSats(satPerVb: Long, inputs: Int, outputs: Int): Long =
        FeeEstimates.clamp(satPerVb) * vBytes(inputs, outputs)
}
