package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.FeeEstimates
import com.jbitmstr.purewallet.core.OnchainFeeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the standard Esplora `/fee-estimates` reply (Blockstream's default chain source). */
class FeeEstimatesTest {

    @Test
    fun blockstreamEstimatesAreRoundedUpToWholeSatPerVb() {
        // Trimmed from a real reply of https://blockstream.info/api/fee-estimates, whose
        // rates are fractional sat/vB (0.124, 1.657, ...).
        val body = "{\"19\":0.124,\"1008\":0.108,\"4\":0.137,\"6\":0.137,\"1\":1.657," +
            "\"2\":1.657,\"3\":1.109,\"144\":0.113}"
        val rates = FeeEstimates.fromEsplora(body)!!
        assertEquals(2L, rates.fastestSatPerVb)
        assertEquals(2L, rates.halfHourSatPerVb)
        assertEquals(1L, rates.hourSatPerVb)
        assertEquals(1L, rates.minimumSatPerVb)
    }

    @Test
    fun wholeNumberRatesPassThrough() {
        val rates = FeeEstimates.fromEsplora("""{"1":16,"2":14,"3":8,"6":5,"144":2,"1008":1}""")!!
        assertEquals(16L, rates.fastestSatPerVb)
        assertEquals(8L, rates.halfHourSatPerVb)
        assertEquals(5L, rates.hourSatPerVb)
        assertEquals(1L, rates.minimumSatPerVb)
    }

    @Test
    fun aQuietMempoolNeverGoesBelowOneSatPerVb() {
        val rates = FeeEstimates.fromEsplora("""{"1":0.2,"2":0.2,"3":0.15,"6":0.1}""")!!
        assertEquals(1L, rates.fastestSatPerVb)
        assertEquals(1L, rates.halfHourSatPerVb)
        assertEquals(1L, rates.hourSatPerVb)
        assertEquals(1L, rates.minimumSatPerVb)
    }

    @Test
    fun aMissingTargetFallsBackToTheNextCoarserEstimate() {
        val rates = FeeEstimates.fromEsplora("""{"1":12,"1008":2}""")!!
        assertEquals(12L, rates.fastestSatPerVb)
        assertEquals(2L, rates.halfHourSatPerVb)
        assertEquals(2L, rates.hourSatPerVb)
        assertEquals(2L, rates.minimumSatPerVb)
    }

    @Test
    fun prettyPrintedBodyIsReadToo() {
        val body = "{\n  \"1\": 21.5,\n  \"3\": 9.0,\n  \"6\": 4.25,\n  \"1008\": 1.0\n}"
        val rates = FeeEstimates.fromEsplora(body)!!
        assertEquals(22L, rates.fastestSatPerVb)
        assertEquals(9L, rates.halfHourSatPerVb)
        assertEquals(5L, rates.hourSatPerVb)
        assertEquals(1L, rates.minimumSatPerVb)
    }

    @Test
    fun emptyOrBrokenRepliesGiveNothing() {
        assertNull(FeeEstimates.fromEsplora(""))
        assertNull(FeeEstimates.fromEsplora("{}"))
        assertNull(FeeEstimates.fromEsplora("not json"))
        assertNull(FeeEstimates.fromEsplora("""{"error":"rate limited"}"""))
        assertNull(FeeEstimates.fromEsplora("""{"1":0,"3":0}"""))
    }

    // ------------------------------------------------------- hostile replies

    @Test
    fun aQuotedNumberInsideSomeOtherFieldIsNotARate() {
        // A regex over the whole body would read the 999999 in this string as target 1.
        val body = """{"note":"\"1\": 999999","1":3,"3":2}"""
        val rates = FeeEstimates.fromEsplora(body)!!
        assertEquals(3L, rates.fastestSatPerVb)
        assertEquals(2L, rates.halfHourSatPerVb)
    }

    @Test
    fun aRateThatIsNotANumberIsSkipped() {
        val body = """{"1":"fast","3":4,"6":null}"""
        val rates = FeeEstimates.fromEsplora(body)!!
        assertEquals(4L, rates.fastestSatPerVb)
        assertEquals(4L, rates.halfHourSatPerVb)
        assertEquals(4L, rates.hourSatPerVb)
    }

    @Test
    fun anAbsurdRateIsCappedAtTheCeiling() {
        val rates = FeeEstimates.fromEsplora("""{"1":1e9,"3":500,"6":2}""")!!
        assertEquals(FeeEstimates.MAX_SAT_PER_VB, rates.fastestSatPerVb)
        assertEquals(500L, rates.halfHourSatPerVb)
        assertEquals(2L, rates.hourSatPerVb)
    }

    @Test
    fun everyRateStaysInsideTheAllowedRange() {
        assertEquals(1L, FeeEstimates.clamp(0L))
        assertEquals(1L, FeeEstimates.clamp(-40L))
        assertEquals(1L, FeeEstimates.clamp(Long.MIN_VALUE))
        assertEquals(1000L, FeeEstimates.clamp(1_000_001L))
        assertEquals(1000L, FeeEstimates.clamp(Long.MAX_VALUE))
        assertEquals(12L, FeeEstimates.clamp(12L))
        assertEquals(1L, FeeEstimates.satPerVb(Double.NaN))
        assertEquals(1000L, FeeEstimates.satPerVb(Double.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------- total fee estimate

    @Test
    fun theEstimatedFeeIsRateTimesSize() {
        // 11 vB overhead + one 68 vB input + two 31 vB outputs = 141 vB.
        assertEquals(141L, OnchainFeeEstimate.vBytes(inputs = 1, outputs = 2))
        assertEquals(110L, OnchainFeeEstimate.vBytes(inputs = 1, outputs = 1))
        assertEquals(2_256L, OnchainFeeEstimate.feeSats(16L, inputs = 1, outputs = 2))
        assertEquals(1_760L, OnchainFeeEstimate.feeSats(16L, inputs = 1, outputs = 1))
    }

    @Test
    fun theEstimatedFeeUsesTheSameClampAsTheSend() {
        assertEquals(
            OnchainFeeEstimate.feeSats(1000L, inputs = 1, outputs = 2),
            OnchainFeeEstimate.feeSats(9_999_999L, inputs = 1, outputs = 2)
        )
    }
}
