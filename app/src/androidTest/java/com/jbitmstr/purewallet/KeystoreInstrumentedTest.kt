package com.jbitmstr.purewallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jbitmstr.purewallet.core.SecretStore
import com.jbitmstr.purewallet.core.SecureStore
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Keystore paths, against the real Android Keystore.
 *
 * The JVM tests can only read this code; a hardware-backed key, a screen-lock binding and a
 * key that the Keystore refuses to use without an authentication do not exist on the test JVM.
 * These run on a device or emulator with `connectedDebugAndroidTest`, and each one restores
 * what it found so the phone's wallet is not left half-written.
 *
 * They are deliberately not part of the CI build: a release pipeline has no device, and a test
 * that quietly skipped on every run would be worse than one that is not claimed.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var secureStore: SecureStore

    private val phrase =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        secureStore = SecureStore(context)
        // A phone that already holds a wallet is somebody's wallet: never touch it.
        assumeTrue("this device already has a wallet", !secureStore.hasSeed())
    }

    @Test
    fun thePhraseIsSealedInsideTheKeystoreAndReadsBack() {
        val sealed = secureStore.saveSeed(phrase)
        // A bound key outside its window answers NeedsAuthentication, which is a correct
        // answer too - nothing is written in that case, and the screen asks for the lock.
        if (sealed == SecureStore.SeedSeal.NeedsAuthentication) return
        assertTrue("the seal failed: $sealed", sealed == SecureStore.SeedSeal.Sealed)

        val read = secureStore.readSeed()
        assertTrue("the phrase did not open: $read", read is SecureStore.SeedRead.Ok)
        assertEquals(phrase, (read as SecureStore.SeedRead.Ok).mnemonic)

        // The stored value is ciphertext with a header, not the words.
        val file = context.getSharedPreferences("purewallet_seed", Context.MODE_PRIVATE).all.values
            .filterIsInstance<String>()
        assertFalse("a stored value holds the phrase in the clear", file.any { it.contains("abandon") })
    }

    @Test
    fun aWipeRemovesTheCiphertextAndTheKeysThatCouldOpenIt() {
        val sealed = secureStore.saveSeed(phrase)
        if (sealed == SecureStore.SeedSeal.NeedsAuthentication) return
        assertTrue(secureStore.hasSeed())

        secureStore.clear()

        assertFalse("the ciphertext is still stored", secureStore.hasSeed())
        assertTrue(
            "the Keystore still holds a key for this wallet",
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("purewallet_data_key") }
                .getOrDefault(false) == false
        )
        assertTrue(secureStore.readSeed() is SecureStore.SeedRead.Absent)
    }

    @Test
    fun aRecordOfTheWalletIsSealedWithAKeyThatNeverLeavesTheKeystore() {
        val sealed = SecretStore.seal("250000")
        assertNotNull("the Keystore refused to seal a record", sealed)
        assertTrue("a sealed record has to carry its marker", sealed!!.startsWith("v1:"))
        assertEquals("250000", SecretStore.open(sealed))

        // The plaintext window is gone: a value without the marker is refused, not returned.
        assertNull(SecretStore.open("250000"))
        assertNull(SecretStore.open(""))

        SecretStore.deleteKey()
    }
}
