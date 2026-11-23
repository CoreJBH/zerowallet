package com.corejbh.zerowallet.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The last wallet numbers this device saw, kept in [Prefs] so opening the wallet never
 * flashes ₿0.
 *
 * A start takes seconds — the node has to open its store and sync with Esplora — and until it
 * finishes the balance would read zero, which on a wallet looks like lost funds. The last
 * known totals, chain height and payment list are therefore written after every successful
 * sync and read back while the node comes up. They are a snapshot, not a source of truth: the
 * first real sync replaces them.
 */
data class CachedWallet(
    val onchainSats: Long,
    val onchainSpendableSats: Long,
    val lightningSats: Long,
    val bestHeight: Int?,
    val lastSyncEpochSecs: Long?,
    val payments: List<PaymentRow>
)

internal object WalletCache {

    /** Enough history to fill the screen; the node's own store still has everything. */
    const val MAX_PAYMENTS = 250

    private const val ROW_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'
    private const val FIELD_COUNT = 10

    /** The snapshot to save for [state]. */
    fun snapshotOf(state: WalletState): CachedWallet = CachedWallet(
        onchainSats = state.onchainTotalSats,
        onchainSpendableSats = state.onchainSpendableSats,
        lightningSats = state.lightningBalanceSats,
        bestHeight = state.bestHeight,
        lastSyncEpochSecs = state.lastSyncEpochSecs,
        payments = state.payments.take(MAX_PAYMENTS)
    )

    /**
     * Fills [state] with [cached]. A running or syncing node already owns these numbers, so
     * the snapshot only lands while the wallet is waiting for one.
     */
    fun applyTo(state: WalletState, cached: CachedWallet?): WalletState {
        if (cached == null || state.running || state.syncing) return state
        return state.copy(
            onchainTotalSats = cached.onchainSats,
            onchainSpendableSats = cached.onchainSpendableSats,
            lightningBalanceSats = cached.lightningSats,
            bestHeight = cached.bestHeight ?: state.bestHeight,
            lastSyncEpochSecs = cached.lastSyncEpochSecs ?: state.lastSyncEpochSecs,
            payments = if (cached.payments.isNotEmpty()) cached.payments else state.payments
        )
    }

    fun encodePayments(rows: List<PaymentRow>): String =
        rows.take(MAX_PAYMENTS).joinToString(ROW_SEPARATOR.toString()) { row ->
            listOf(
                row.id,
                if (row.lightning) "1" else "0",
                if (row.incoming) "1" else "0",
                row.status,
                if (row.succeeded) "1" else "0",
                row.amountSats.toString(),
                row.feeSats.toString(),
                row.description.orEmpty(),
                row.timestampSecs.toString(),
                row.detail.orEmpty()
            ).joinToString(FIELD_SEPARATOR.toString()) { field -> escape(field) }
        }

    fun decodePayments(raw: String?): List<PaymentRow> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ROW_SEPARATOR)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { decodeRow(line) }.getOrNull() }
            .take(MAX_PAYMENTS)
            .toList()
    }

    private fun decodeRow(line: String): PaymentRow? {
        val fields = line.split(FIELD_SEPARATOR).map { unescape(it) }
        if (fields.size != FIELD_COUNT) return null
        return PaymentRow(
            id = fields[0],
            lightning = fields[1] == "1",
            incoming = fields[2] == "1",
            status = fields[3],
            succeeded = fields[4] == "1",
            amountSats = fields[5].toLongOrNull() ?: return null,
            feeSats = fields[6].toLongOrNull() ?: return null,
            description = fields[7].ifEmpty { null },
            timestampSecs = fields[8].toLongOrNull() ?: return null,
            detail = fields[9].ifEmpty { null }
        )
    }

    private fun escape(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun unescape(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
