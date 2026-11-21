package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.EsploraFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Esplora endpoint a start tries, and when a failure is the chain source's fault.
 *
 * v1.1.8: a phone whose saved endpoint timed out (`FeerateEstimationUpdateTimeout`) used to
 * give up on the whole node. The saved endpoint now comes first, then the one that last
 * worked here, then the chain's other public endpoints, each deduplicated.
 */
class EsploraFallbackTest {

    private val mainnet = ChainNetwork.MAINNET

    @Test
    fun theSavedEndpointIsTriedFirst() {
        val candidates = EsploraFallback.candidates(
            configured = mainnet.defaultEsploraUrl,
            lastWorking = null,
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = true
        )
        assertEquals("https://blockstream.info/api", candidates.first())
    }

    @Test
    fun theOtherPublicEndpointsFollowInOrder() {
        val candidates = EsploraFallback.candidates(
            configured = mainnet.defaultEsploraUrl,
            lastWorking = null,
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = true
        )
        assertEquals(
            listOf(
                "https://blockstream.info/api",
                "https://mempool.space/api",
                "https://mempool.emzy.de/api"
            ),
            candidates
        )
    }

    @Test
    fun theSavedEndpointIsNotRepeatedAmongTheFallbacks() {
        val candidates = EsploraFallback.candidates(
            configured = "https://mempool.space/api",
            lastWorking = null,
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = true
        )
        assertEquals(1, candidates.count { it == "https://mempool.space/api" })
        assertEquals("https://mempool.space/api", candidates.first())
        assertEquals(listOf("https://blockstream.info/api", "https://mempool.emzy.de/api"), candidates.drop(1))
    }

    @Test
    fun anEndpointThatLastWorkedIsTriedSecond() {
        // A phone that cannot reach Blockstream should not pay for the same timeout on every
        // open: the endpoint that came up last time goes right after the saved one.
        val candidates = EsploraFallback.candidates(
            configured = mainnet.defaultEsploraUrl,
            lastWorking = "https://mempool.space/api",
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = true
        )
        assertEquals(listOf("https://blockstream.info/api", "https://mempool.space/api"), candidates.take(2))
        assertEquals(1, candidates.count { it == "https://mempool.space/api" })
    }

    @Test
    fun aLastWorkingEndpointEqualToTheSavedOneAddsNothing() {
        val candidates = EsploraFallback.candidates(
            configured = mainnet.defaultEsploraUrl,
            lastWorking = "https://blockstream.info/api/",
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = true
        )
        assertEquals(3, candidates.size)
    }

    @Test
    fun anEndpointTheUserEnteredIsTheOnlyOneTried() {
        // Their own Esplora is their choice; a wallet's addresses do not go to a public server
        // because the owner's server was slow.
        val candidates = EsploraFallback.candidates(
            configured = "http://127.0.0.1:3002",
            lastWorking = "https://mempool.space/api",
            fallbacks = mainnet.publicEsploraUrls,
            allowFallbacks = false
        )
        assertEquals(listOf("http://127.0.0.1:3002"), candidates)
    }

    @Test
    fun caseAndTrailingSlashesDoNotCostASecondStart() {
        val candidates = EsploraFallback.candidates(
            configured = "https://Blockstream.info/api/",
            lastWorking = "https://blockstream.info/api",
            fallbacks = listOf("https://blockstream.info/api/", "https://mempool.space/api"),
            allowFallbacks = true
        )
        assertEquals(
            listOf("https://Blockstream.info/api/", "https://mempool.space/api"),
            candidates
        )
    }

    @Test
    fun everyChainFallbackBelongsToItsOwnChain() {
        ChainNetwork.values().forEach { network ->
            val public = network.publicEsploraUrls
            assertEquals("${network.id} keeps its default first", network.defaultEsploraUrl, public.first())
            assertEquals("${network.id} has no repeats", public.size, public.distinct().size)
            val marker = when (network) {
                ChainNetwork.MAINNET -> "/api"
                ChainNetwork.SIGNET -> "/signet/api"
                ChainNetwork.TESTNET -> "/testnet/api"
            }
            public.forEach { url ->
                assertTrue("${network.id}: $url", url.endsWith(marker))
            }
        }
    }

    @Test
    fun onlyTheChainsBuiltInEndpointsCountAsPublic() {
        assertTrue(
            EsploraFallback.isPublicEndpoint(
                "https://blockstream.info/api/",
                mainnet.publicEsploraUrls
            )
        )
        assertFalse(
            EsploraFallback.isPublicEndpoint(
                "https://esplora.example.com/api",
                mainnet.publicEsploraUrls
            )
        )
    }

    // ------------------------------------------------------------ what failed

    @Test
    fun aFeerateTimeoutIsAChainSourceFailure() {
        val failure = RuntimeException(
            "FeerateEstimationUpdateTimeout: Updating fee rate estimates timed out."
        )
        assertTrue(EsploraFallback.isChainSourceFailure(failure))
    }

    @Test
    fun aWrappedFeerateTimeoutIsStillRecognized() {
        val failure = IllegalStateException("start failed", RuntimeException("Updating fee rate estimates timed out."))
        assertTrue(EsploraFallback.isChainSourceFailure(failure))
    }

    @Test
    fun aRefusedLightningPortIsNotAChainSourceFailure() {
        val failure = IllegalStateException("bind failed: EADDRINUSE (Address already in use)")
        assertFalse(EsploraFallback.isChainSourceFailure(failure))
    }

    @Test
    fun anInvalidListeningAddressIsNotAChainSourceFailure() {
        val failure = IllegalArgumentException("Given listening addresses are invalid.")
        assertFalse(EsploraFallback.isChainSourceFailure(failure))
    }

    @Test
    fun anHttpRefusalIsAChainSourceFailure() {
        assertTrue(EsploraFallback.isChainSourceFailure(RuntimeException("HTTP 429 Too Many Requests")))
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun aSwitchNamesBothServers() {
        val message = EsploraFallback.switchedMessage(
            "https://blockstream.info/api",
            "https://mempool.space/api"
        )
        assertTrue(message, message.contains("Blockstream"))
        assertTrue(message, message.contains("mempool.space"))
    }

    @Test
    fun anExhaustedStartListsWhatWasTried() {
        val message = EsploraFallback.exhaustedMessage(
            listOf(
                "https://blockstream.info/api",
                "https://mempool.space/api",
                "https://mempool.emzy.de/api"
            ),
            RuntimeException("Updating fee rate estimates timed out.")
        )
        assertTrue(message, message.contains("Blockstream"))
        assertTrue(message, message.contains("mempool.space"))
        assertTrue(message, message.contains("mempool.emzy.de"))
        assertTrue(message, message.contains("timed out"))
    }

    @Test
    fun aSingleCustomEndpointIsNamedInItsOwnFailure() {
        val message = EsploraFallback.exhaustedMessage(
            listOf("http://127.0.0.1:3002"),
            null
        )
        assertTrue(message, message.contains("127.0.0.1"))
    }
}
