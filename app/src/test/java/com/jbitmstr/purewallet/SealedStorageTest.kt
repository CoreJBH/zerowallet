package com.jbitmstr.purewallet

import android.content.SharedPreferences
import com.jbitmstr.purewallet.core.CachedWallet
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.PaymentNotes
import com.jbitmstr.purewallet.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed storage.
 *
 * v1.1.14: when the Keystore refused to seal, `Prefs` and `PaymentNotes` used to write the
 * plain value instead (`SecretStore.seal(value) ?: value`). The seal exists so that a balance,
 * a block height and the note describing who was paid do not sit in a preferences file that
 * another app with access to the app's data can read, so the fallback undid the protection
 * exactly when it mattered - a Keystore that is broken or has been reset. Both now write
 * nothing when the value cannot be sealed.
 *
 * The sealing functions are injected, so these tests run on the JVM without an Android
 * Keystore: a "working" sealer marks the value, a "broken" one returns null as the real one
 * does on a device whose Keystore refuses.
 */
class SealedStorageTest {

    private fun workingSealer(value: String) = "v1:<sealed>$value"

    private fun snapshot(
        onchain: Long = 250_000L,
        lightning: Long = 1_000L
    ) = CachedWallet(
        onchainSats = onchain,
        onchainSpendableSats = onchain,
        lightningSats = lightning,
        bestHeight = 842_000,
        lastSyncEpochSecs = 1_763_000_000L,
        payments = emptyList()
    )

    // ------------------------------------------------------------------ balance cache

    @Test
    fun aSnapshotIsWrittenWholeWhenEveryValueCanBeSealed() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, ::workingSealer, { value -> value.removePrefix("v1:<sealed>") })

        assertTrue(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))

        val cached = prefs.cachedWallet(ChainNetwork.MAINNET)
        assertNotNull(cached)
        assertEquals(250_000L, cached!!.onchainSats)
        assertEquals(1_000L, cached.lightningSats)
        assertEquals(842_000, cached.bestHeight)
    }

    @Test
    fun nothingIsWrittenInPlainTextWhenTheKeystoreRefusesToSeal() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, { null }, { null })

        assertFalse(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))

        // Neither the amounts, the height nor the sync time are anywhere in the file, and the
        // save marker is not written either: a dated snapshot with no numbers behind it would
        // read as a wallet that holds nothing.
        assertTrue(store.values.isEmpty())
        assertNull(prefs.cachedWallet(ChainNetwork.MAINNET))
    }

    @Test
    fun aFailedSealLeavesThePreviousSealedSnapshotInPlace() {
        val store = FakeSharedPreferences()
        var sealing = true
        val prefs = Prefs(
            store,
            { value -> if (sealing) workingSealer(value) else null },
            { value -> value.removePrefix("v1:<sealed>") }
        )

        assertTrue(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot(onchain = 400_000L)))
        sealing = false
        assertFalse(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot(onchain = 900_000L)))

        // The good snapshot is still the one read back, and the file never gained a plain
        // value for the sync that failed. The save timestamp is a Long, not a record.
        assertEquals(400_000L, prefs.cachedWallet(ChainNetwork.MAINNET)!!.onchainSats)
        val storedStrings = store.values.filterValues { it is String }.values.map { it as String }
        assertTrue(storedStrings.all { it.startsWith("v1:") })
    }

    @Test
    fun oneValueThatCannotBeSealedStopsTheWholeSnapshot() {
        val store = FakeSharedPreferences()
        // The payments blob is the largest record and the one most likely to hit a size or
        // cipher limit; failing any single value must not leave the other five behind.
        var calls = 0
        val prefs = Prefs(
            store,
            { value ->
                calls += 1
                if (calls == 3) null else workingSealer(value)
            },
            { value -> value.removePrefix("v1:<sealed>") }
        )

        assertFalse(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))

        assertTrue(store.values.isEmpty())
    }

    @Test
    fun clearingEveryCacheForgetsEachChainNotJustTheActiveOne() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, ::workingSealer, { value -> value.removePrefix("v1:<sealed>") })

        ChainNetwork.entries.forEach { network ->
            assertTrue(prefs.saveCachedWallet(network, snapshot()))
        }
        prefs.clearAllCachedWallets()

        ChainNetwork.entries.forEach { network ->
            assertNull("${network.id} kept a snapshot", prefs.cachedWallet(network))
        }
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun clearingOneChainLeavesTheOthers() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, ::workingSealer, { value -> value.removePrefix("v1:<sealed>") })
        assertTrue(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))
        assertTrue(prefs.saveCachedWallet(ChainNetwork.SIGNET, snapshot()))

        prefs.clearCachedWallet(ChainNetwork.SIGNET)

        assertNotNull(prefs.cachedWallet(ChainNetwork.MAINNET))
        assertNull(prefs.cachedWallet(ChainNetwork.SIGNET))
    }

    // ------------------------------------------------------------------ payment notes

    @Test
    fun aPaymentNoteIsSealedBeforeItIsStored() {
        val store = FakeSharedPreferences()
        val notes = PaymentNotes(store, ::workingSealer)

        assertTrue(notes.put("abc123", "Coffee"))

        assertEquals("v1:<sealed>Coffee", store.values["abc123"])
    }

    @Test
    fun aPaymentNoteThatCannotBeSealedIsNotStoredAtAll() {
        val store = FakeSharedPreferences()
        val notes = PaymentNotes(store, { null })

        assertFalse(notes.put("abc123", "Coffee"))

        assertTrue(store.values.isEmpty())
    }

    @Test
    fun aPaymentNoteFromAnOlderBuildIsRefused() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "Coffee"
        val notes = PaymentNotes(store, ::workingSealer)

        // No `v1:` marker: not a note this build wrote, and 1.2.0 has no window in which an
        // unsealed value is handed back. A description is the cheapest thing in the file to
        // lose; a way to read plain values back is not.
        assertNull(notes.get("abc123"))
    }

    // ------------------------------------------------ plain values are no longer read

    /** The production unsealer: only a sealed value opens. */
    private val unsealer: (String) -> String? = { value ->
        if (!value.startsWith("v1:")) null else value.removePrefix("v1:<sealed>")
    }

    @Test
    fun anOlderBuildsPlainNumbersAreNotRead() {
        val store = FakeSharedPreferences()
        store.values["cache_saved_at_mainnet"] = 1_763_000_000_000L
        store.values["cache_onchain_mainnet"] = 250_000L
        store.values["cache_lightning_mainnet"] = 1_000L
        val prefs = Prefs(store, ::workingSealer, unsealer)

        // 1.1.10 stored the numbers themselves. Handing those back - which 1.1.18 did while
        // it re-sealed them - made an unsealed value a balance on the screen, so 1.2.0 refuses
        // them all. The snapshot is a cache of what the node will say on its next sync.
        val cached = prefs.cachedWallet(ChainNetwork.MAINNET)
        assertEquals(0L, cached?.onchainSats)
        assertEquals(0L, cached?.lightningSats)
        assertNull(cached?.bestHeight)
    }

    @Test
    fun aTamperedPlainValueIsNotABalance() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, ::workingSealer, unsealer)
        assertTrue(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))

        // A value this app did not write never becomes what the wallet shows, in either
        // direction: a number, or a string without the seal marker.
        store.values["cache_onchain_mainnet"] = 999_999_999L
        assertEquals(0L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)
        store.values["cache_onchain_mainnet"] = "999999999"
        assertEquals(0L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)
    }

    // ------------------------------------------------------ payment notes on the same rule

    @Test
    fun aNoteThatCannotBeReadIsNoNote() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "not a sealed value"
        val notes = PaymentNotes(store, ::workingSealer)
        assertNull(notes.get("abc123"))
    }

    @Test
    fun theNewestNoteIsDroppedFirstWhenTheFileIsFull() {
        val store = FakeSharedPreferences()
        repeat(501) { index -> store.values["hash$index"] = "v1:<sealed>note" }
        val notes = PaymentNotes(store, ::workingSealer)

        assertTrue(notes.put("newhash", "Coffee"))

        // The file stays bounded, and the note just written is not one of the ones evicted.
        assertEquals("v1:<sealed>Coffee", store.values["newhash"])
        assertTrue("the file grew past its limit: ${store.values.size}", store.values.size <= 500)
    }
}

/** In-memory [SharedPreferences] for the JVM tests; `android.jar` here is only a stub. */
internal class FakeSharedPreferences : SharedPreferences {

    val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removed += key }

        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            write()
            return true
        }

        override fun apply() = write()

        private fun write() {
            if (clearFirst) {
                values.clear()
                clearFirst = false
            }
            removed.forEach { values.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            removed.clear()
        }
    }
}