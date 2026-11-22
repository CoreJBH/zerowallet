package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.PaymentInput
import com.jbitmstr.purewallet.core.PaymentInputKind
import com.jbitmstr.purewallet.core.PaymentInputParser
import com.jbitmstr.purewallet.core.ResolvedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payment request parsing, including the chain an on-chain address belongs to. */
class PaymentInputTest {

    private val mainnetBech32 = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val mainnetP2pkh = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
    private val testnetBech32 = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private val testnetP2pkh = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"

    @Test
    fun bip21AmountIsReadInSats() {
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.001&label=Coffee")
        assertEquals(PaymentInputKind.ONCHAIN, parsed.kind)
        assertEquals(mainnetBech32, parsed.address)
        assertEquals(100_000L, parsed.amountSats)
        assertEquals("Coffee", parsed.label)
    }

    @Test
    fun bip21WithMoreThanTheSupplyIsNotAnAmount() {
        // An amount above 21 million bitcoin is not a sum this chain has; it is refused as a
        // request, not read as "no amount" - the difference is whether the payer is then
        // shown an empty box and invited to send a sum of their own.
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=21000001")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
        assertTrue(parsed.rejection.orEmpty().contains("amount"))
    }

    @Test
    fun aMalformedAmountIsRefusedInsteadOfIgnored() {
        // The eighth review: `amount=abc` used to be dropped on the floor, and the send sheet
        // then offered an editable amount for a request that had named one.
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=abc")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
        assertTrue(parsed.rejection.orEmpty().contains("cannot read"))
    }

    @Test
    fun anEmptyAmountIsRefused() {
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.rejection != null)
    }

    @Test
    fun anAmountWrittenAsAnExponentIsRefused() {
        // BIP-21 spells the amount in plain decimal; 1e-3 is a different language for the
        // same number, and a wallet that guesses at it can disagree with the next one.
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=1e-3")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
    }

    @Test
    fun aNegativeOrZeroAmountIsRefused() {
        assertEquals(
            PaymentInputKind.UNKNOWN,
            PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=-0.001").kind
        )
        assertEquals(
            PaymentInputKind.UNKNOWN,
            PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0").kind
        )
        assertEquals(
            PaymentInputKind.UNKNOWN,
            PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.00000000").kind
        )
    }

    @Test
    fun anAmountFinerThanASatoshiIsRefused() {
        // 0.000000019 BTC is 1.9 satoshis: rounding it to 1 would underpay the receiver and
        // rounding up would overpay it, so the request is not paid at all.
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.000000019")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
        assertTrue(parsed.rejection.orEmpty().contains("8"))
    }

    @Test
    fun eightDecimalsAndTrailingZerosAreStillAnAmount() {
        val exact = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.00000001")
        assertEquals(PaymentInputKind.ONCHAIN, exact.kind)
        assertEquals(1L, exact.amountSats)

        val trailing = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.00100000000")
        assertEquals(PaymentInputKind.ONCHAIN, trailing.kind)
        assertEquals(100_000L, trailing.amountSats)
    }

    @Test
    fun bip21WithTwoAmountsIsRefused() {
        // BIP-21 does not say which duplicate wins, so two wallets can show different
        // amounts for the same QR. This wallet refuses the request instead of picking one.
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?amount=0.001&amount=1.0"
        )
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
        assertTrue(parsed.rejection.orEmpty().contains("more than one amount"))
    }

    @Test
    fun bip21WithTheSameAmountTwiceIsAlsoRefused() {
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?amount=0.001&label=A&amount=0.001"
        )
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.rejection != null)
    }

    @Test
    fun aRequiredParameterThisWalletDoesNotKnowIsRefused() {
        // BIP-21's `req-` prefix says the payer has to understand the parameter. Ignoring it
        // and paying the address would be paying a request that was not read: a future
        // extension could change where the funds go.
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?amount=0.001&req-futurething=1"
        )
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.amountSats)
        assertTrue(parsed.rejection.orEmpty().contains("req-futurething"))
    }

    @Test
    fun aRequiredParameterIsRefusedEvenWithoutAnAmount() {
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?req-split=2")
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.rejection.orEmpty().contains("does not support"))
    }

    @Test
    fun aParameterThatOnlyLooksLikeARequirementIsStillIgnored() {
        // Only `req-` is reserved. `request=` is an ordinary parameter nobody defined, and it
        // has always been ignored rather than treated as a condition.
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=0.001&request=1")
        assertEquals(PaymentInputKind.ONCHAIN, parsed.kind)
        assertEquals(100_000L, parsed.amountSats)
    }

    @Test
    fun aSingleAmountWithOtherParametersIsStillPaid() {
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?label=Coffee&amount=0.001&message=One"
        )
        assertEquals(PaymentInputKind.ONCHAIN, parsed.kind)
        assertEquals(100_000L, parsed.amountSats)
        assertNull(parsed.rejection)
    }

    @Test
    fun mainnetAddressesMatchOnlyMainnet() {
        assertTrue(PaymentInputParser.matchesNetwork(mainnetBech32, mainnet = true))
        assertTrue(PaymentInputParser.matchesNetwork(mainnetP2pkh, mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork(mainnetBech32, mainnet = false))
        assertFalse(PaymentInputParser.matchesNetwork(mainnetP2pkh, mainnet = false))
    }

    @Test
    fun testChainAddressesMatchOnlyTestChains() {
        assertTrue(PaymentInputParser.matchesNetwork(testnetBech32, mainnet = false))
        assertTrue(PaymentInputParser.matchesNetwork(testnetP2pkh, mainnet = false))
        assertFalse(PaymentInputParser.matchesNetwork(testnetBech32, mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork(testnetP2pkh, mainnet = true))
    }

    @Test
    fun emptyAndMalformedAddressesMatchNothing() {
        assertFalse(PaymentInputParser.matchesNetwork("", mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork("   ", mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork("bc1 qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork("bc1", mainnet = true))
        assertFalse(PaymentInputParser.matchesNetwork("hello", mainnet = true))
    }

    @Test
    fun lightningAndPeerFormsAreUnchanged() {
        val invoice = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4js"
        assertEquals(PaymentInputKind.BOLT11, PaymentInputParser.parse(invoice).kind)
        assertEquals(
            PaymentInputKind.BOLT12_OFFER,
            PaymentInputParser.parse("lno1" + "q".repeat(60)).kind
        )
        val peer = "02" + "ab".repeat(32) + "@10.0.0.2:9735"
        assertEquals(PaymentInputKind.PEER, PaymentInputParser.parse(peer).kind)
    }

    // ------------------------------------------------------ BOLT12 shape checks

    private val offer = "lno1" + "q".repeat(80)

    @Test
    fun aTruncatedBolt12OfferIsNotAnOffer() {
        // The prefix alone used to be enough, so this reached the node and failed there.
        assertEquals(PaymentInputKind.UNKNOWN, PaymentInputParser.parse("lno1q").kind)
        assertEquals(PaymentInputKind.UNKNOWN, PaymentInputParser.parse("lno1").kind)
        // Bech32 never carries b, i or o, and only one '1' separator.
        assertEquals(
            PaymentInputKind.UNKNOWN,
            PaymentInputParser.parse("lno1" + "b".repeat(80)).kind
        )
        assertEquals(
            PaymentInputKind.UNKNOWN,
            PaymentInputParser.parse("lno1" + "q".repeat(40) + "1" + "q".repeat(40)).kind
        )
    }

    @Test
    fun aWellFormedOfferIsStillAnOffer() {
        assertTrue(PaymentInputParser.looksLikeBolt12Offer(offer))
        assertEquals(PaymentInputKind.BOLT12_OFFER, PaymentInputParser.parse(offer).kind)
        assertEquals(
            PaymentInputKind.BOLT12_OFFER,
            PaymentInputParser.parse("lightning:$offer").kind
        )
        assertEquals(
            PaymentInputKind.BOLT12_INVOICE,
            PaymentInputParser.parse("lni1" + "q".repeat(80)).kind
        )
    }

    // ------------------------------------------------- BIP-21 lightning= hand-off

    private val bolt11 = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4js"

    @Test
    fun theLightningParameterOfABip21QrIsKept() {
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?amount=0.001&lightning=$bolt11"
        )
        assertEquals(PaymentInputKind.ONCHAIN, parsed.kind)
        assertEquals(100_000L, parsed.amountSats)
        assertEquals(bolt11, parsed.lightning)

        val lightning = PaymentInputParser.parseLightningValue(parsed.lightning!!)
        assertEquals(PaymentInputKind.BOLT11, lightning?.kind)
        assertEquals(bolt11, lightning?.bolt11)

        val fromOffer = PaymentInputParser.parseLightningValue(offer)
        assertEquals(PaymentInputKind.BOLT12_OFFER, fromOffer?.kind)
        assertEquals(offer, fromOffer?.bolt12Offer)
    }

    @Test
    fun twoLightningParametersAreRefused() {
        // Two invoices in one QR is the same ambiguity as two amounts: another wallet can
        // settle the other one, and the payer would never know which was paid.
        val other = "lnbc10u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4js"
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?amount=0.001&lightning=$bolt11&lightning=$other"
        )
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertNull(parsed.lightning)
        assertTrue(parsed.rejection.orEmpty().contains("more than one Lightning request"))
    }

    @Test
    fun theSameLightningParameterTwiceIsAlsoRefused() {
        val parsed = PaymentInputParser.parse(
            "bitcoin:$mainnetBech32?lightning=$bolt11&lightning=$bolt11"
        )
        assertEquals(PaymentInputKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.rejection != null)
    }

    @Test
    fun aBrokenLightningParameterIsIgnored() {
        assertNull(PaymentInputParser.parseLightningValue(""))
        assertNull(PaymentInputParser.parseLightningValue("not-an-invoice"))
        assertNull(PaymentInputParser.parseLightningValue("lno1q"))
        assertNull(PaymentInputParser.parseLightningValue("lnr1" + "q".repeat(80)))
    }

    @Test
    fun amountsStayWithinTheSupply() {
        // The parser is not the only gate; this pins the constant the node checks against.
        assertEquals(2_100_000_000_000_000L, Fmt.MAX_SATS)
    }

    @Test
    fun sendAllAppliesOnlyToAnEditableOnchainRequest() {
        // A fixed-amount QR is paid for exactly what it says: sweeping the balance would
        // silently pay a different payment than the one on screen, so it is not offered.
        val fixedQr = ResolvedTarget(
            input = PaymentInput(
                kind = PaymentInputKind.ONCHAIN,
                raw = "bitcoin:$mainnetBech32?amount=0.001",
                address = mainnetBech32,
                amountSats = 100_000L
            ),
            description = "",
            amountSats = 100_000L,
            amountEditable = false
        )
        assertFalse(fixedQr.allowsSendAll)

        val editableAddress = ResolvedTarget(
            input = PaymentInput(
                kind = PaymentInputKind.ONCHAIN,
                raw = mainnetBech32,
                address = mainnetBech32
            ),
            description = "",
            amountSats = null,
            amountEditable = true
        )
        assertTrue(editableAddress.allowsSendAll)

        // Lightning is not swept: send-all is an on-chain balance action, whatever amount
        // editability the invoice itself has.
        val lightning = ResolvedTarget(
            input = PaymentInput(
                kind = PaymentInputKind.BOLT11,
                raw = "lnbc",
                bolt11 = "lnbc"
            ),
            description = "",
            amountSats = null,
            amountEditable = true
        )
        assertFalse(lightning.allowsSendAll)
    }
}
