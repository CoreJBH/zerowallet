package com.jbitmstr.purewallet.core

import kotlin.math.ceil

/**
 * Reads the standard Esplora `/fee-estimates` body.
 *
 * The reply is a JSON object whose keys are confirmation targets in blocks and whose values
 * are fee rates in sat/vB, for example
 * `{"1": 1.657, "3": 1.109, "6": 0.137, "1008": 0.108}`. Blockstream serves exactly this,
 * while mempool.space additionally offers the numbered `/v1/fees/recommended` helper; this
 * parser keeps the wallet working on either, and plain Kotlin keeps it unit tested.
 *
 * Rates are rounded up and never below 1 sat/vB, the floor every node accepts.
 */
object FeeEstimates {

    fun fromEsplora(body: String): FeeRates? {
        val byTarget = LinkedHashMap<Int, Double>()
        ENTRY.findAll(body).forEach { match ->
            val target = match.groupValues[1].toIntOrNull() ?: return@forEach
            val rate = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (rate > 0.0) byTarget[target] = rate
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

    private fun satPerVb(rate: Double): Long = ceil(rate).toLong().coerceAtLeast(1L)

    private val ENTRY = Regex("\"(\\d+)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)")
}
