package com.jbitmstr.purewallet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The market layer: the fiat figure on the balance card and the fee rate a send is planned
 * at.
 *
 * Both are reads from public endpoints that have nothing to do with the wallet's keys or its
 * node, which is why they are apart from the payment layer: a price loop that keeps running,
 * or an estimate that fails, cannot reach a payment or a start from here.
 *
 * The price is read only while the wallet is on screen. A request every 60 seconds from a
 * wallet nobody is looking at costs battery and tells the price endpoints the user's address
 * for no reason at all.
 */
internal class MarketLayer(private val core: WalletCore) {

    /** Starts the price loop if it is not already running; does nothing in the background. */
    fun startLoop() {
        if (!core.onScreen) return
        if (core.fiatJob?.isActive == true) return
        core.fiatJob = core.scope.launch {
            while (isActive) {
                val quote = Market.fetchFiat()
                if (quote != null) {
                    core.update { it.copy(fiat = quote) }
                }
                delay(FIAT_POLL_MILLIS)
            }
        }
    }

    /** Stops it with the screen; the last quote stays on the card until a new one arrives. */
    fun stopLoop() {
        core.fiatJob?.cancel()
        core.fiatJob = null
    }

    /**
     * The rate the send screen plans at, or null when the chain source did not answer.
     *
     * A failed estimate returns null rather than a hardcoded rate: the send screen disables
     * the send button while there is no rate, so a phone that is offline (or an endpoint that
     * is down) holds the send instead of quietly paying a made-up fee.
     */
    suspend fun estimateFeeRate(priority: FeePriority): Long? = withContext(Dispatchers.IO) {
        estimatedSendRate(priority, Market.fetchFeeRates(core.currentEsploraUrl(core.prefs.network)))
    }

    companion object {
        /** How often the price is read while the wallet is on screen (and only then). */
        private const val FIAT_POLL_MILLIS = 60_000L
    }
}
