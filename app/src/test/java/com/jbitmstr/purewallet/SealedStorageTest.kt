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
        val prefs = Prefs(store, ::workingSealer, { value, _ -> value.removePrefix("v1:<sealed>") })

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
        val prefs = Prefs(store, { null }, { _, _ -> null })

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
            { value, _ -> value.removePrefix("v1:<sealed>") }
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
            { value, _ -> value.removePrefix("v1:<sealed>") }
        )

        assertFalse(prefs.saveCachedWallet(ChainNetwork.MAINNET, snapshot()))

        assertTrue(store.values.isEmpty())
    }

    @Test
    fun clearingEveryCacheForgetsEachChainNotJustTheActiveOne() {
        val store = FakeSharedPreferences()
        val prefs = Prefs(store, ::workingSealer, { value, _ -> value.removePrefix("v1:<sealed>") })

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
        val prefs = Prefs(store, ::workingSealer, { value, _ -> value.removePrefix("v1:<sealed>") })
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
    fun aPaymentNoteFromAnOlderBuildIsStillRead() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "Coffee"
        val notes = PaymentNotes(store, ::workingSealer)

        // No `v1:` marker: the plain value older builds wrote reads back as it is.
        assertEquals("Coffee", notes.get("abc123"))
    }
    // ------------------------------------------ plain values, once the migration has run

    /** The production unsealer's policy: a plain value only while the migration has not run. */
    private val policyUnsealer: (String, Boolean) -> String? = { value, allowPlain ->
        if (!allowPlain && !value.startsWith("v1:")) null else value.removePrefix("v1:<sealed>")
    }

    @Test
    fun anOlderBuildsPlainNumbersStillReadBeforeTheMigration() {
        val store = FakeSharedPreferences()
        store.values["cache_saved_at_mainnet"] = 1_763_000_000_000L
        store.values["cache_onchain_mainnet"] = 250_000L
        store.values["cache_lightning_mainnet"] = 1_000L
        val prefs = Prefs(store, ::workingSealer, policyUnsealer)

        // 1.1.10 stored the numbers themselves; the upgrade has not sealed them yet.
        assertEquals(250_000L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)
    }

    @Test
    fun theMigrationSealsThemAndThenAPlainValueIsRefused() {
        val store = FakeSharedPreferences()
        store.values["cache_saved_at_mainnet"] = 1_763_000_000_000L
        store.values["cache_onchain_mainnet"] = 250_000L
        store.values["cache_lightning_mainnet"] = 1_000L
        val prefs = Prefs(store, ::workingSealer, policyUnsealer)

        prefs.migratePlainTextValues()

        assertEquals("v1:<sealed>250000", store.values["cache_onchain_mainnet"])
        assertEquals("v1:<sealed>1000", store.values["cache_lightning_mainnet"])
        assertEquals(250_000L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)

        // Anything plain that turns up after this is not something this app wrote: a
        // tampered or corrupted file must not become the balance on the screen.
        store.values["cache_onchain_mainnet"] = 999_999_999L
        assertEquals(0L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)
    }

    @Test
    fun aMigrationThatCannotSealLeavesTheMarkerForTheNextStart() {
        val store = FakeSharedPreferences()
        store.values["cache_saved_at_mainnet"] = 1_763_000_000_000L
        store.values["cache_onchain_mainnet"] = 250_000L
        val prefs = Prefs(store, { null }, policyUnsealer)

        prefs.migratePlainTextValues()

        assertEquals(250_000L, store.values["cache_onchain_mainnet"])
        assertFalse(
            "the marker is only set when every value is sealed",
            store.values.containsKey("plain_values_migrated_1_1_18")
        )
        // And the value is still readable, so the history is not lost while it waits.
        assertEquals(250_000L, prefs.cachedWallet(ChainNetwork.MAINNET)?.onchainSats)
    }

    @Test
    fun theMigrationRunsOnce() {
        val store = FakeSharedPreferences()
        store.values["cache_saved_at_mainnet"] = 1_763_000_000_000L
        store.values["cache_onchain_mainnet"] = 250_000L
        var seals = 0
        val prefs = Prefs(store, { value -> seals += 1; workingSealer(value) }, policyUnsealer)

        prefs.migratePlainTextValues()
        prefs.migratePlainTextValues()

        assertEquals(1, seals)
    }

    // ------------------------------------------------------ payment notes on the same rule

    @Test
    fun aPlainNoteIsStillReadWhileTheMigrationHasNotRun() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "Coffee"
        val notes = PaymentNotes(store, ::workingSealer)
        assertEquals("Coffee", notes.get("abc123"))
    }

    @Test
    fun aPlainNoteIsRefusedOnceTheNotesAreMigrated() {
        val store = FakeSharedPreferences()
        store.values["plain_notes_migrated_1_1_18"] = true
        store.values["abc123"] = "Coffee"
        val notes = PaymentNotes(store, ::workingSealer)
        assertNull(notes.get("abc123"))
    }

    @Test
    fun theNoteMigrationSealsWhatItFindsAndRecordsIt() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "Coffee"
        store.values["def456"] = "v1:<sealed>Taxi"
        val notes = PaymentNotes(store, ::workingSealer)

        notes.migratePlainTextNotes()

        assertEquals("v1:<sealed>Coffee", store.values["abc123"])
        assertEquals("v1:<sealed>Taxi", store.values["def456"])
        assertEquals(true, store.values["plain_notes_migrated_1_1_18"])
    }

    @Test
    fun aNoteTheKeystoreWillNotSealKeepsTheMigrationOpen() {
        val store = FakeSharedPreferences()
        store.values["abc123"] = "Coffee"
        val notes = PaymentNotes(store, { null })

        notes.migratePlainTextNotes()

        assertEquals("Coffee", store.values["abc123"])
        assertFalse(store.values.containsKey("plain_notes_migrated_1_1_18"))
    }

    @Test
    fun theMigrationMarkerIsNeverEvictedToMakeRoomForANote() {
        val store = FakeSharedPreferences()
        store.values["plain_notes_migrated_1_1_18"] = true
        repeat(501) { index -> store.values["hash$index"] = "v1:<sealed>note" }
        val notes = PaymentNotes(store, ::workingSealer)

        assertTrue(notes.put("newhash", "Coffee"))

        assertEquals(true, store.values["plain_notes_migrated_1_1_18"])
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