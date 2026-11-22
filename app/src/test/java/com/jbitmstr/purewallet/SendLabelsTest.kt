package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.PaymentInputKind
import com.jbitmstr.purewallet.ui.sheets.feeSummary
import com.jbitmstr.purewallet.ui.sheets.referenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the send screen says after a payment and under its amount.
 *
 * v1.1.14: a BIP-21 QR is an on-chain request even when it carries a `lightning=` invoice,
 * so the identifier under a Lightning payment of one was labelled "Transaction id" while the
 * value shown was a payment hash. And the fee line read "₿1,234 sats", counting the unit
 * twice because `Fmt.sats` already writes the `₿` marker.
 */
class SendLabelsTest {

    @Test
    fun aBip21QrPaidOverLightningShowsAPaymentHashNotATransactionId() {
        assertEquals("Payment hash", referenceLabel(sentOverLightning = true, PaymentInputKind.ONCHAIN))
    }

    @Test
    fun anOnchainSendStillShowsATransactionId() {
        assertEquals("Transaction id", referenceLabel(sentOverLightning = false, PaymentInputKind.ONCHAIN))
    }

    @Test
    fun aLightningInvoiceShowsItsPaymentHash() {
        assertEquals("Payment hash", referenceLabel(sentOverLightning = true, PaymentInputKind.BOLT11))
        assertEquals("Payment hash", referenceLabel(sentOverLightning = true, PaymentInputKind.BOLT12_OFFER))
    }

    @Test
    fun noRequestStillHasALabel() {
        assertEquals("Payment id", referenceLabel(sentOverLightning = false, null))
    }

    @Test
    fun theFeeLineNamesTheUnitOnce() {
        val line = feeSummary(estimate = 1_234L, feeRate = 12L, vBytes = 141L)

        assertTrue(line, line.contains("₿1,234"))
        assertFalse("the unit is written twice: $line", line.contains("sats "))
        assertTrue("the rate is what the sentence is for", line.contains("12 sat/vB"))
    }

    @Test
    fun theFeeLineSaysWhyItIsEmptyWhileTheRateLoads() {
        val line = feeSummary(estimate = null, feeRate = null, vBytes = 141L)

        assertFalse(line, line.contains("₿"))
        assertTrue(line, line.contains("Reading the fee rate"))
    }
}
