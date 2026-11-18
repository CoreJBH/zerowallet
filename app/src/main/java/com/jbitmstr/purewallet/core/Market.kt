package com.jbitmstr.purewallet.core

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

    suspend fun fetchFiat(): FiatQuote? = withContext(Dispatchers.IO) {
        coingecko() ?: upbit() ?: mempoolUsd()
    }

    suspend fun fetchFeeRates(esploraBaseUrl: String): FeeRates? = withContext(Dispatchers.IO) {
        val body = httpGet(esploraBaseUrl.trimEnd('/') + "/v1/fees/recommended") ?: return@withContext null
        runCatching {
            val json = JSONObject(body)
            FeeRates(
                fastestSatPerVb = json.optLong("fastestFee", 0L).coerceAtLeast(1L),
                halfHourSatPerVb = json.optLong("halfHourFee", 0L).coerceAtLeast(1L),
                hourSatPerVb = json.optLong("hourFee", 0L).coerceAtLeast(1L),
                minimumSatPerVb = json.optLong("minimumFee", 1L).coerceAtLeast(1L)
            )
        }.getOrNull()
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
                setRequestProperty("User-Agent", "PUREWALLET/1.0")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
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
