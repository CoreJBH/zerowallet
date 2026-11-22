package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.FeeEstimates
import com.jbitmstr.purewallet.core.FeeRates
import com.jbitmstr.purewallet.core.Market
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mempool.space `/v1/fees/recommended` fallback used when an Esplora endpoint has no
 * `/fee-estimates` of its own.
 *
 * v1.1.16 read it with `optLong(key, 0)`, so a field that was missing, spelled differently or
 * not a number became 0, and the clamp turned that into 1 sat/vB. A broken reply therefore
 * looked exactly like a calm mempool, and the send screen would have paid with it without
 * saying anything. Every field is now required: a reply that cannot answer the question is
 * null, which is what makes the send sheet say so and offer Retry.
 */
class MarketFeeTest {

    private fun rates(body: String): FeeRates? = Market.parseRecommendedFees(body)

    @Test
    fun aRealReplyIsRead() {
        val body = """{"fastestFee":18,"halfHourFee":14,"hourFee":9,"economyFee":5,"minimumFee":2}"""
        val parsed = rates(body)!!
        assertEquals(18L, parsed.fastestSatPerVb)
        assertEquals(14L, parsed.halfHourSatPerVb)
        assertEquals(9L, parsed.hourSatPerVb)
        assertEquals(2L, parsed.minimumSatPerVb)
    }

    @Test
    fun fractionalRatesAreRoundedUpLikeEveryOtherEstimate() {
        val parsed = rates("""{"fastestFee":1.2,"halfHourFee":1.1,"hourFee":0.9,"minimumFee":0.8}""")!!
        assertEquals(2L, parsed.fastestSatPerVb)
        assertEquals(2L, parsed.halfHourSatPerVb)
        assertEquals(1L, parsed.hourSatPerVb)
        assertEquals(1L, parsed.minimumSatPerVb)
    }

    @Test
    fun malformedJsonIsNull() {
        assertNull(rates("not json at all"))
        assertNull(rates(""))
        assertNull(rates("{"))
        assertNull(rates("[]"))
    }

    @Test
    fun aMissingFieldIsNullRatherThanOneSatPerVb() {
        // This is the regression: 1 sat/vB used to be invented for every one of these.
        assertNull(rates("""{"fastestFee":18,"halfHourFee":14,"hourFee":9}"""))
        assertNull(rates("""{"fastestFee":18,"halfHourFee":14,"minimumFee":2}"""))
        assertNull(rates("""{"fastestFee":18,"hourFee":9,"minimumFee":2}"""))
        assertNull(rates("""{"halfHourFee":14,"hourFee":9,"minimumFee":2}"""))
        assertNull(rates("""{}"""))
    }

    @Test
    fun aFieldThatIsNotANumberIsNull() {
        assertNull(rates("""{"fastestFee":"fast","halfHourFee":14,"hourFee":9,"minimumFee":2}"""))
        assertNull(rates("""{"fastestFee":null,"halfHourFee":14,"hourFee":9,"minimumFee":2}"""))
        assertNull(rates("""{"fastestFee":{"a":1},"halfHourFee":14,"hourFee":9,"minimumFee":2}"""))
    }

    @Test
    fun aZeroOrNegativeRateIsNull() {
        assertNull(rates("""{"fastestFee":0,"halfHourFee":14,"hourFee":9,"minimumFee":2}"""))
        assertNull(rates("""{"fastestFee":18,"halfHourFee":-3,"hourFee":9,"minimumFee":2}"""))
    }

    @Test
    fun anAbsurdRateIsClampedNotTrusted() {
        val parsed = rates("""{"fastestFee":500000,"halfHourFee":14,"hourFee":9,"minimumFee":2}""")!!
        assertEquals(FeeEstimates.MAX_SAT_PER_VB, parsed.fastestSatPerVb)
    }

    @Test
    fun aQuotedNumberStillReads() {
        val parsed = rates("""{"fastestFee":"18","halfHourFee":"14","hourFee":"9","minimumFee":"2"}""")!!
        assertEquals(18L, parsed.fastestSatPerVb)
        assertEquals(2L, parsed.minimumSatPerVb)
    }
}
