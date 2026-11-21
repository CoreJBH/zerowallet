package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.FeeEstimates
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
}
