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

    @Test
    fun amountsStayWithinTheSupply() {
        // The parser is not the only gate; this pins the constant the node checks against.
        assertEquals(2_100_000_000_000_000L, Fmt.MAX_SATS)
    }
}
