package com.corejbh.zerowallet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Live BTC price, used to show fiat equivalents in the balance card. */
data class FiatQuote(
    val krwPerBtc: Double,
    val usdPerBtc: Double?,
    val source: String,
    val fetchedAtEpochSecs: Long
)

/** Esplora/mempool fee estimates in sat/vB. */
data class FeeRates(
    val fastestSatPerVb: Long,
    val halfHourSatPerVb: Long,
    val hourSatPerVb: Long,
    val minimumSatPerVb: Long
)

/**
 * Public price and fee endpoints. No API keys and no account: the wallet never sends
 * anything about the user to these services beyond the plain price request.
 */
object Market {

    /**
     * Largest reply this wallet will read from a price or fee endpoint (256 KiB). The
     * replies it asks for are a few hundred bytes of JSON; without a cap a hostile or
     * misconfigured endpoint could make the app buffer an arbitrarily large body in the
     * middle of a sync loop.
     */
    const val MAX_BODY_BYTES = 256 * 1024

    suspend fun fetchFiat(): FiatQuote? = withContext(Dispatchers.IO) {
        coingecko() ?: upbit() ?: mempoolUsd()
    }

    /**
     * Fee estimates for the configured Esplora endpoint.
     *
     * The standard Esplora `/fee-estimates` reply is asked for first, because every
     * endpoint (Blockstream's included) serves it in one request; the mempool.space
     * `/v1/fees/recommended` helper is only a fallback for deployments that expose nothing
     * else. Without either, the caller falls back to its own conservative defaults.
     */
    suspend fun fetchFeeRates(esploraBaseUrl: String): FeeRates? = withContext(Dispatchers.IO) {
        val base = esploraBaseUrl.trimEnd('/')
        httpGet("$base/fee-estimates")?.let { body -> FeeEstimates.fromEsplora(body) }
            ?: httpGet("$base/v1/fees/recommended")?.let { body -> parseRecommendedFees(body) }
    }

    /**
     * Reads the mempool.space `/v1/fees/recommended` body.
     *
     * Every rate is required, and a reply that does not carry a usable number for one of
     * them is null, not a guess. It used to be read with `optLong(key, 0)`, which turns a
     * missing or non-numeric field into 0 and then, after the clamp, into a perfectly
     * ordinary-looking 1 sat/vB: a broken endpoint produced a rate the send screen would
     * have shown and paid with, and nothing anywhere said the estimate had failed. Nulled
     * here, the screen has to say so and offer to read again (see [FeeGate]).
     *
     * Visible to tests: this is the parser, and it is checked against malformed replies as
     * well as real ones.
     */
    internal fun parseRecommendedFees(body: String): FeeRates? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        fun rate(key: String): Long? {
            if (!json.has(key)) return null
            // optDouble coerces a quoted number ("1.5") and gives NaN for anything else.
            val value = json.optDouble(key, Double.NaN)
            if (!value.isFinite() || value <= 0.0) return null
            // Clamped like every other estimate: a stray or absurd number cannot decide what
            // this wallet pays.
            return FeeEstimates.satPerVb(value)
        }

        val fastest = rate("fastestFee") ?: return null
        val halfHour = rate("halfHourFee") ?: return null
        val hour = rate("hourFee") ?: return null
        val minimum = rate("minimumFee") ?: return null
        return FeeRates(
            fastestSatPerVb = fastest,
            halfHourSatPerVb = halfHour,
            hourSatPerVb = hour,
            minimumSatPerVb = minimum
        )
    }

    private fun coingecko(): FiatQuote? {
        val body = httpGet(
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=krw,usd"
        ) ?: return null
        return runCatching {
            val bitcoin = JSONObject(body).getJSONObject("bitcoin")
            val krw = bitcoin.getDouble("krw")
            val usd = if (bitcoin.has("usd")) bitcoin.getDouble("usd") else null
            if (krw <= 0.0) null else FiatQuote(
                krwPerBtc = krw,
                usdPerBtc = usd,
                source = "CoinGecko",
                fetchedAtEpochSecs = System.currentTimeMillis() / 1000L
            )
        }.getOrNull()
    }

    private fun upbit(): FiatQuote? {
        val body = httpGet("https://api.upbit.com/v1/ticker?markets=KRW-BTC") ?: return null
        return runCatching {
            val array = org.json.JSONArray(body)
            val price = array.getJSONObject(0).getDouble("trade_price")
            if (price <= 0.0) null else FiatQuote(
                krwPerBtc = price,
                usdPerBtc = null,
                source = "Upbit",
                fetchedAtEpochSecs = System.currentTimeMillis() / 1000L
            )
        }.getOrNull()
    }

    private fun mempoolUsd(): FiatQuote? {
        val body = httpGet("https://mempool.space/api/v1/prices") ?: return null
        return runCatching {
            val json = JSONObject(body)
            val usd = json.getDouble("USD")
            if (usd <= 0.0) null else FiatQuote(
                krwPerBtc = 0.0,
                usdPerBtc = usd,
                source = "mempool.space",
                fetchedAtEpochSecs = System.currentTimeMillis() / 1000L
            )
        }.getOrNull()
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "ZeroWallet/1.0")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        text.append(buffer, 0, read)
                        if (text.length > MAX_BODY_BYTES) return null
                    }
                    text.toString()
                }
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
