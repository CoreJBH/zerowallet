package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.PaymentInputKind
import com.jbitmstr.purewallet.core.PaymentInputParser
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
        val parsed = PaymentInputParser.parse("bitcoin:$mainnetBech32?amount=21000001")
        assertEquals(PaymentInputKind.ONCHAIN, parsed.kind)
        assertNull(parsed.amountSats)
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
}
