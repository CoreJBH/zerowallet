package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.lightningAgreesWithOnchain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the two halves of a BIP-21 QR are the same payment.
 *
 * Lightning invoices price in millisatoshis, and the send screen shows satoshis. Comparing the
 * rounded figures made a 1,000.9-sat invoice look like the 1,000-sat `amount=` beside it, so
 * the wallet could pay the on-chain half of a QR whose Lightning half asked for a different
 * sum - and report it as the payment the QR meant. The comparison is in millisatoshis.
 */
class AmountAgreementTest {

    @Test
    fun asubSatoshiDifferenceIsADifferentPayment() {
        assertFalse(lightningAgreesWithOnchain(1_000L, 1_000_900L))
        assertFalse(lightningAgreesWithOnchain(1_000L, 999_100L))
    }

    @Test
    fun theSameAmountInSatoshisAndMillisatoshisAgrees() {
        assertTrue(lightningAgreesWithOnchain(1_000L, 1_000_000L))
    }

    @Test
    fun aLargeInvoiceAgreesWithItsOwnSats() {
        val sats = 2_100_000_000_000_000L
        assertTrue(lightningAgreesWithOnchain(sats, sats * 1000L))
    }

    @Test
    fun aSideWithoutAnAmountAgreesWithAnything() {
        assertTrue(lightningAgreesWithOnchain(null, 1_000_900L))
        assertTrue(lightningAgreesWithOnchain(1_000L, null))
        assertTrue(lightningAgreesWithOnchain(null, null))
    }
}
