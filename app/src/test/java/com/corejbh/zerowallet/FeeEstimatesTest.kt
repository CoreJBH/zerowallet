package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.FeeEstimates
import com.corejbh.zerowallet.core.FeePriority
import com.corejbh.zerowallet.core.FeeRates
import com.corejbh.zerowallet.core.FeeWarning
import com.corejbh.zerowallet.core.OnchainFeeEstimate
import com.corejbh.zerowallet.core.estimatedSendRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ------------------------------------------------------- high-fee warning

    @Test
    fun anOrdinaryFeeIsPaidWithoutADialog() {
        // 16 sat/vB on 141 vB = 2,256 sats for 100,000 sats sent.
        val fee = OnchainFeeEstimate.feeSats(16L, inputs = 1, outputs = 2)
        assertFalse(FeeWarning.isExcessive(16L, fee, 100_000L))
        assertEquals(2, FeeWarning.percentOfAmount(fee, 100_000L))
    }

    @Test
    fun aRateOfAHundredOrMoreAlwaysWarns() {
        assertTrue(FeeWarning.isExcessive(100L, 14_100L, 5_000_000L))
        assertTrue(FeeWarning.isExcessive(900L, null, null))
        assertFalse(FeeWarning.isExcessive(99L, 13_959L, 5_000_000L))
    }

    @Test
    fun aFeeThatEatsThePaymentWarns() {
        // 2,256 sats of fee on a 5,000 sat payment is 45%, with a rate that is not unusual.
        assertTrue(FeeWarning.isExcessive(16L, 2_256L, 5_000L))
    }

    @Test
    fun aPercentageOfATinyFeeIsNotWorthADialog() {
        // 300 sats of fee on 1,000 sats is 30%, but the absolute fee is trivial.
        assertFalse(FeeWarning.isExcessive(2L, 300L, 1_000L))
    }

    @Test
    fun sendingEverythingOnlyHasTheRateRule() {
        assertFalse(FeeWarning.isExcessive(16L, 2_256L, null))
        assertTrue(FeeWarning.isExcessive(150L, 21_150L, null))
        assertNull(FeeWarning.percentOfAmount(2_256L, null))
        assertNull(FeeWarning.percentOfAmount(2_256L, 0L))
    }

    @Test
    fun anOnchainSendWaitsUntilTheRateHasArrived() {
        // The regression: with no rate both rules were skipped, so pressing send while the
        // estimate was still loading paid at the fallback rate with no confirmation at all.
        assertTrue(FeeWarning.isWaitingForRate(onchain = true, rateSatPerVb = null))
        assertFalse(FeeWarning.isWaitingForRate(onchain = true, rateSatPerVb = 900L))
        // A Lightning payment is not judged by an on-chain rate.
        assertFalse(FeeWarning.isWaitingForRate(onchain = false, rateSatPerVb = null))
    }

    @Test
    fun aFailedFeeEstimateIsANullRateNotAFallback() {
        // The regression: a send screen that could not reach the chain source used to fall
        // back to hardcoded 8/16 sat/vB and pay that silently. No estimate, no rate.
        assertNull(estimatedSendRate(FeePriority.NORMAL, null))
        assertNull(estimatedSendRate(FeePriority.FAST, null))
    }

    @Test
    fun anArrivedFeeEstimateIsThePrioritysRate() {
        val rates = FeeRates(
            fastestSatPerVb = 21L,
            halfHourSatPerVb = 12L,
            hourSatPerVb = 6L,
            minimumSatPerVb = 1L
        )
        assertEquals(12L, estimatedSendRate(FeePriority.NORMAL, rates))
        assertEquals(21L, estimatedSendRate(FeePriority.FAST, rates))
    }
}
