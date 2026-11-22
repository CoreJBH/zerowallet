package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.SeedStorage
import com.jbitmstr.purewallet.core.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key that seals the recovery phrase: which generation a stored file belongs to, what
 * that generation says about its protection, and the wording the user is shown when the key
 * is not bound to the screen lock.
 *
 * The ninth review's point was that the key material used to be generated in software and
 * imported into the Keystore. [SecureStore] now asks
 * `KeyGenerator.getInstance(AES, "AndroidKeyStore")` for the key, which no unit test can
 * exercise (there is no Keystore on a desktop JVM), so the parts that can be checked without
 * Android are checked here, and `SeedKeySourceTest` reads the source for the rest.
 */
class SeedStorageTest {

    @Test
    fun theCurrentKeyIsTriedFirstAndTheEncryptOnlyHalfIsNeverTried() {
        assertEquals(SeedStorage.CURRENT, SeedStorage.readOrder.first())
        assertTrue(SeedStorage.readOrder.contains(SeedStorage.OLD_PAIR))
        assertTrue(SeedStorage.readOrder.contains(SeedStorage.OLD_SINGLE))
        // The pair's encrypt half can never open anything; trying it would only add a failed
        // decrypt to every start.
        assertEquals(3, SeedStorage.readOrder.size)
    }

    @Test
    fun everyGenerationThatMayBeOnDiskIsKnown() {
        assertEquals(SeedStorage.readOrder.toSet(), SeedStorage.allKinds.toSet())
    }

    @Test
    fun anOldCiphertextIsResealedAndTheCurrentOneIsNot() {
        assertTrue(SeedStorage.needsReseal(SeedStorage.OLD_PAIR))
        assertTrue(SeedStorage.needsReseal(SeedStorage.OLD_SINGLE))
        assertFalse(SeedStorage.needsReseal(SeedStorage.CURRENT))
    }

    @Test
    fun markersRoundTripAndUnknownOnesSayNothing() {
        SeedStorage.allKinds.forEach { kind ->
            assertEquals(kind, SeedStorage.kindOfMarker(SeedStorage.marker(kind)))
        }
        assertNull(SeedStorage.kindOfMarker(null))
        assertNull(SeedStorage.kindOfMarker(""))
        assertNull(SeedStorage.kindOfMarker("something-from-a-later-build"))
        assertNull(SeedStorage.marker("something-from-a-later-build"))
    }

    @Test
    fun aBoundKeyReportsTheStrongState() {
        assertEquals(
            SecureStore.SeedProtection.DEVICE_LOCK,
            SeedStorage.protectionOf(SeedStorage.CURRENT, authRequired = true)
        )
    }

    @Test
    fun anUnboundKeyIsReportedAsSuchRatherThanAsStrong() {
        assertEquals(
            SecureStore.SeedProtection.UNBOUND,
            SeedStorage.protectionOf(SeedStorage.CURRENT, authRequired = false)
        )
        // A key the Keystore cannot describe is not evidence of strength either.
        assertEquals(
            SecureStore.SeedProtection.UNBOUND,
            SeedStorage.protectionOf(SeedStorage.CURRENT, authRequired = null)
        )
        assertEquals(
            SecureStore.SeedProtection.UNBOUND,
            SeedStorage.protectionOf(null, authRequired = null)
        )
    }

    @Test
    fun anOldUnboundWalletSaysWhyAndWhatWouldFixIt() {
        val noLock = SeedStorage.unboundDetail(SeedStorage.REASON_NO_SCREEN_LOCK, deviceSecureNow = false)
        assertTrue(noLock, noLock.contains("no screen lock"))
        assertTrue(noLock, noLock.contains("Set a PIN"))

        // The phone gained a lock since: the key cannot be bound retroactively, so the text
        // has to say what actually fixes it.
        val sinceLocked = SeedStorage.unboundDetail(SeedStorage.REASON_NO_SCREEN_LOCK, deviceSecureNow = true)
        assertTrue(sinceLocked, sinceLocked.contains("Restoring the wallet"))
    }

    @Test
    fun aKeystoreThatRefusedTheBoundKeySaysSo() {
        listOf(SeedStorage.REASON_GENERATE_REFUSED, SeedStorage.LEGACY_REASON_IMPORT_REFUSED)
            .forEach { reason ->
                val text = SeedStorage.unboundDetail(reason, deviceSecureNow = true)
                assertTrue(text, text.contains("would not keep the wallet key bound"))
            }
    }

    @Test
    fun aReasonThisBuildDoesNotKnowStillExplainsTheState() {
        val text = SeedStorage.unboundDetail(null, deviceSecureNow = true)
        assertTrue(text, text.contains("not bound to the screen lock"))
    }
}
