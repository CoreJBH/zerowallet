package com.corejbh.zerowallet.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formatting helpers. Amounts are always handled in satoshis internally and only
 * converted for display.
 */
object Fmt {

    const val SATS_PER_BTC = 100_000_000.0

    /** Satoshis in 21 million BTC, the whole supply: more than this is not an amount. */
    const val MAX_SATS: Long = 21_000_000L * 100_000_000L

    private val SATS_PER_BTC_DECIMAL: java.math.BigDecimal =
        java.math.BigDecimal.valueOf(100_000_000L)
    private val MAX_BTC: java.math.BigDecimal = java.math.BigDecimal.valueOf(21_000_000L)

    fun sats(value: Long): String = "₿" + String.format(Locale.US, "%,d", value)

    fun btc(satoshis: Long): String = String.format(Locale.US, "%.8f", satoshis / SATS_PER_BTC)

    fun btcShort(satoshis: Long): String {
        val text = btc(satoshis).trimEnd('0').trimEnd('.')
        return if (text.isEmpty()) "0" else text
    }

    fun krw(value: Long): String = "₩" + String.format(Locale.US, "%,d", value)

    fun usd(value: Long): String = "$" + String.format(Locale.US, "%,d", value)

    /** Converts satoshis to fiat using a BTC price in the fiat currency. */
    fun fiatFromSats(satoshis: Long, pricePerBtc: Double): Long =
        Math.round(satoshis / SATS_PER_BTC * pricePerBtc)

    /** Converts a fiat amount back to satoshis, rounding down. */
    fun satsFromFiat(fiat: Double, pricePerBtc: Double): Long =
        if (pricePerBtc <= 0.0) 0L else Math.floor(fiat / pricePerBtc * SATS_PER_BTC).toLong()

    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        val format = SimpleDateFormat("MMM d", Locale.ENGLISH)
        return format.format(Date(epochSeconds * 1000L))
    }

    fun dateTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        val format = SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH)
        return format.format(Date(epochSeconds * 1000L))
    }

    /** Shortens a node id or channel id for dense list rows. */
    fun shortId(id: String, head: Int = 8, tail: Int = 6): String {
        if (id.length <= head + tail + 1) return id
        return id.take(head) + "…" + id.takeLast(tail)
    }

    /** Parses a plain satoshi amount typed by the user, tolerating spaces and commas. */
    fun parseSats(input: String): Long? {
        val cleaned = input.replace(",", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * Parses a BTC amount typed by the user ("0.001") into satoshis.
     *
     * Exact decimal arithmetic, not [Double]: `0.1 + 0.2` style error and values such as
     * `0.00000001` rounding the wrong way have no place in an amount that gets signed. More
     * than the 21 million supply, or a value whose satoshi count would overflow, is refused
     * (null) instead of wrapping into a small or negative amount. Sub-satoshi digits are
     * dropped, never rounded up: the wallet never pays more than the user typed.
     */
    fun parseBtcToSats(input: String): Long? {
        val cleaned = input.replace(",", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        val btc = try {
            java.math.BigDecimal(cleaned)
        } catch (e: NumberFormatException) {
            return null
        }
        if (btc.signum() <= 0) return null
        if (btc > MAX_BTC) return null
        val sats = btc.multiply(SATS_PER_BTC_DECIMAL).setScale(0, java.math.RoundingMode.DOWN)
        if (sats.signum() <= 0) return null
        if (sats > java.math.BigDecimal.valueOf(MAX_SATS)) return null
        return sats.toLong()
    }
}
