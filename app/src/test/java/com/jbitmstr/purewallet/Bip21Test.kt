package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Bip21
import com.jbitmstr.purewallet.core.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BIP-21 amount URI behind the receive sheet's on-chain tab.
 *
 * The amount field was hidden on that tab, so `bitcoin:<addr>?amount=...` could only ever be
 * built with an empty amount: the code that looked like it added one was unreachable. The
 * builder now lives on its own, and these are the rules it has to keep.
 */
class Bip21Test {

    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    @Test
    fun withoutAnAmountTheUriIsJustTheAddress() {
        assertEquals("bitcoin:$address", Bip21.uri(address))
    }

    @Test
    fun anAmountIsWrittenInBtcWithEightDecimals() {
        assertEquals("bitcoin:$address?amount=0.00001000", Bip21.uri(address, 1_000L))
        assertEquals("bitcoin:$address?amount=1.00000000", Bip21.uri(address, 100_000_000L))
    }

    @Test
    fun theSmallestAmountStillRoundTrips() {
        assertEquals("bitcoin:$address?amount=0.00000001", Bip21.uri(address, 1L))
        assertEquals(1L, Fmt.parseBtcToSats("0.00000001"))
    }

    @Test
    fun theAmountParsesBackToTheSameSats() {
        val uri = Bip21.uri(address, 123_456_789L)
        val amount = uri.substringAfter("amount=")
        assertEquals(123_456_789L, Fmt.parseBtcToSats(amount))
    }

    @Test
    fun zeroOrNegativeSatsIsNoAmount() {
        assertEquals("bitcoin:$address", Bip21.uri(address, 0L))
        assertEquals("bitcoin:$address", Bip21.uri(address, -5L))
    }

    @Test
    fun moreThanTheSupplyIsRefused() {
        val tooMuch = Fmt.MAX_SATS + 1
        val failed = runCatching { Bip21.uri(address, tooMuch) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun aBlankAddressIsRefused() {
        assertTrue(runCatching { Bip21.uri("   ") }.isFailure)
    }

    @Test
    fun anAddressThatIsAlreadyAUriIsNotWrappedTwice() {
        assertTrue(runCatching { Bip21.uri("bitcoin:$address?amount=1") }.isFailure)
        assertTrue(runCatching { Bip21.uri("$address?amount=1") }.isFailure)
    }

    @Test
    fun theAddressIsReadBackOutOfTheUri() {
        assertEquals(address, Bip21.addressOf("bitcoin:$address?amount=0.00001000"))
        assertEquals(address, Bip21.addressOf("bitcoin:$address"))
        // A bare address is returned as it is, so one code path serves both.
        assertEquals(address, Bip21.addressOf(address))
        assertFalse(Bip21.addressOf("bitcoin:$address?amount=1").contains("amount"))
    }
}
