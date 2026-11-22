package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers BTC amount parsing: exact decimal conversion, the 21 million cap, overflow and
 * every input that is not an amount. This is the value a payment signs, so a rounding
 * error or a wrap-around is not cosmetic.
 */
class FormattingTest {

    @Test
    fun wholeAndFractionalBtcBecomeSats() {
        assertEquals(100_000_000L, Fmt.parseBtcToSats("1"))
        assertEquals(100_000L, Fmt.parseBtcToSats("0.001"))
        assertEquals(1L, Fmt.parseBtcToSats("0.00000001"))
        assertEquals(12_345_678L, Fmt.parseBtcToSats("0.12345678"))
    }

    @Test
    fun separatorsAndSpacesAreTolerated() {
        assertEquals(100_000_000L, Fmt.parseBtcToSats("1.0"))
        assertEquals(150_000_000L, Fmt.parseBtcToSats(" 1.5 "))
        assertEquals(150_000_000L, Fmt.parseBtcToSats("1.5"))
    }

    @Test
    fun subSatoshiDigitsAreDroppedNotRoundedUp() {
        // 0.000000015 BTC is one and a half satoshis; paying two would be paying more than
        // the user typed, which no amount box may do.
        assertEquals(1L, Fmt.parseBtcToSats("0.000000015"))
        assertEquals(123_456_789_012L, Fmt.parseBtcToSats("1234.56789012"))
    }

    @Test
    fun decimalArithmeticIsExact() {
        // The classic binary floating point traps. As Double these drift; as decimal they
        // do not, and the signed amount is what the user read on screen.
        assertEquals(10_000_000L, Fmt.parseBtcToSats("0.1"))
        assertEquals(30_000_000L, Fmt.parseBtcToSats("0.3"))
        assertEquals(110_000_000L, Fmt.parseBtcToSats("1.1"))
        assertEquals(29_000_000L, Fmt.parseBtcToSats("0.29"))
    }

    @Test
    fun theWholeSupplyIsAcceptedAndOneSatoshiMoreIsNot() {
        assertEquals(Fmt.MAX_SATS, Fmt.parseBtcToSats("21000000"))
        assertEquals(21_000_000_000_000L, Fmt.parseBtcToSats("210000"))
        assertNull(Fmt.parseBtcToSats("21000001"))
        assertNull(Fmt.parseBtcToSats("99999999999999999999999999"))
    }

    @Test
    fun zeroNegativeAndGarbageAreRefused() {
        assertNull(Fmt.parseBtcToSats(""))
        assertNull(Fmt.parseBtcToSats("   "))
        assertNull(Fmt.parseBtcToSats("0"))
        assertNull(Fmt.parseBtcToSats("0.0"))
        assertNull(Fmt.parseBtcToSats("-1"))
        assertNull(Fmt.parseBtcToSats("abc"))
        assertNull(Fmt.parseBtcToSats("1.2.3"))
        assertNull(Fmt.parseBtcToSats("."))
        assertNull(Fmt.parseBtcToSats("0.000000001"))
    }

    @Test
    fun satsParseStaysPositive() {
        assertEquals(1_000L, Fmt.parseSats("1,000"))
        assertNull(Fmt.parseSats("0"))
        assertNull(Fmt.parseSats("-5"))
        assertNull(Fmt.parseSats(""))
    }
}
