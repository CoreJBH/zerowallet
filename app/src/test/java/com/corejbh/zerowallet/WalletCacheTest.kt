package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.CachedWallet
import com.corejbh.zerowallet.core.PaymentRow
import com.corejbh.zerowallet.core.WalletCache
import com.corejbh.zerowallet.core.WalletState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot behind a wallet that never flashes ₿0.
 *
 * v1.1.8: a start takes seconds, and until it finished the balance read zero. The last known
 * totals, height, sync time and payment rows are written after every real sync and read back
 * while the node comes up.
 */
class WalletCacheTest {

    private fun row(
        id: String = "abc123",
        description: String? = "Coffee",
        detail: String? = null,
        incoming: Boolean = true
    ) = PaymentRow(
        id = id,
        lightning = true,
        incoming = incoming,
        status = "SUCCEEDED",
        succeeded = true,
        amountSats = 1_500L,
        feeSats = 2L,
        description = description,
        timestampSecs = 1_763_000_000L,
        detail = detail
    )

    @Test
    fun aSnapshotOfTheStateKeepsEveryBalance() {
        val state = WalletState(
            onchainTotalSats = 120_000L,
            onchainSpendableSats = 100_000L,
            lightningBalanceSats = 30_000L,
            bestHeight = 870_123,
            lastSyncEpochSecs = 1_763_000_500L,
            payments = listOf(row())
        )
        val cached = WalletCache.snapshotOf(state)
        assertEquals(120_000L, cached.onchainSats)
        assertEquals(100_000L, cached.onchainSpendableSats)
        assertEquals(30_000L, cached.lightningSats)
        assertEquals(870_123, cached.bestHeight)
        assertEquals(1_763_000_500L, cached.lastSyncEpochSecs)
        assertEquals(1, cached.payments.size)
    }

    // ------------------------------------------------------------- hydration

    @Test
    fun aWaitingWalletShowsTheLastKnownNumbers() {
        val waiting = WalletState(seedPresent = true, bootstrapped = true)
        val cached = CachedWallet(
            onchainSats = 120_000L,
            onchainSpendableSats = 100_000L,
            lightningSats = 30_000L,
            bestHeight = 870_123,
            lastSyncEpochSecs = 1_763_000_500L,
            payments = listOf(row())
        )
        val hydrated = WalletCache.applyTo(waiting, cached)
        assertEquals(150_000L, hydrated.totalSats)
        assertEquals(100_000L, hydrated.onchainSpendableSats)
        assertEquals(870_123, hydrated.bestHeight)
        assertEquals(1_763_000_500L, hydrated.lastSyncEpochSecs)
        assertEquals(1, hydrated.payments.size)
        // Hydration is numbers only: it must not pretend the node is up or the wallet is set up.
        assertEquals(false, hydrated.running)
        assertEquals(false, hydrated.syncing)
    }

    @Test
    fun noSnapshotLeavesTheWalletAlone() {
        val waiting = WalletState(seedPresent = true, bootstrapped = true)
        val hydrated = WalletCache.applyTo(waiting, null)
        assertEquals(0L, hydrated.totalSats)
        assertNull(hydrated.lastSyncEpochSecs)
    }

    @Test
    fun aRunningNodeKeepsItsOwnNumbers() {
        val live = WalletState(
            running = true,
            onchainTotalSats = 5L,
            lightningBalanceSats = 6L,
            bestHeight = 900_000
        )
        val cached = CachedWallet(
            onchainSats = 120_000L,
            onchainSpendableSats = 100_000L,
            lightningSats = 30_000L,
            bestHeight = 870_123,
            lastSyncEpochSecs = 1_763_000_500L,
            payments = listOf(row())
        )
        val hydrated = WalletCache.applyTo(live, cached)
        assertEquals(11L, hydrated.totalSats)
        assertEquals(900_000, hydrated.bestHeight)
        assertTrue(hydrated.payments.isEmpty())
    }

    @Test
    fun aSyncingNodeKeepsItsOwnNumbers() {
        val live = WalletState(running = true, syncing = true, onchainTotalSats = 5L)
        val cached = CachedWallet(120_000L, 100_000L, 30_000L, 870_123, 1L, listOf(row()))
        assertEquals(5L, WalletCache.applyTo(live, cached).totalSats)
    }

    // ------------------------------------------------------------ history rows

    @Test
    fun paymentRowsSurviveTheRoundTrip() {
        val rows = listOf(
            row(id = "one", description = "Coffee\tnear\nhome", detail = "Confirmed at block 870123"),
            row(id = "two", description = null, detail = null, incoming = false)
        )
        val decoded = WalletCache.decodePayments(WalletCache.encodePayments(rows))
        assertEquals(rows, decoded)
    }

    @Test
    fun aDescriptionWithSeparatorsStaysOneRow() {
        val encoded = WalletCache.encodePayments(listOf(row(description = "a\tb\nc")))
        assertEquals(1, encoded.lines().filter { it.isNotBlank() }.size)
        assertEquals("a\tb\nc", WalletCache.decodePayments(encoded).single().description)
    }

    @Test
    fun anEmptyDescriptionComesBackAsNoDescription() {
        val decoded = WalletCache.decodePayments(WalletCache.encodePayments(listOf(row(description = ""))))
        assertNull(decoded.single().description)
        assertNull(decoded.single().detail)
    }

    @Test
    fun aDamagedLineIsSkippedAndTheRestSurvives() {
        val encoded = WalletCache.encodePayments(listOf(row(id = "one"), row(id = "two")))
        val damaged = encoded + "\nnot\tenough\tfields\n12345"
        val decoded = WalletCache.decodePayments(damaged)
        assertEquals(listOf("one", "two"), decoded.map { it.id })
    }

    @Test
    fun nothingSavedDecodesToNothing() {
        assertTrue(WalletCache.decodePayments(null).isEmpty())
        assertTrue(WalletCache.decodePayments("").isEmpty())
    }

    @Test
    fun theHistoryKeepsABoundedNumberOfRows() {
        val many = (1..(WalletCache.MAX_PAYMENTS + 25)).map { row(id = "id$it") }
        val cached = WalletCache.snapshotOf(WalletState(payments = many))
        assertEquals(WalletCache.MAX_PAYMENTS, cached.payments.size)
        assertEquals(
            WalletCache.MAX_PAYMENTS,
            WalletCache.decodePayments(WalletCache.encodePayments(many)).size
        )
    }
}
