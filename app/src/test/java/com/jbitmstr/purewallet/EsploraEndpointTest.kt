package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.EsploraUrl
import com.jbitmstr.purewallet.core.esploraChangeNeedsRestart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mainnet chain source and when a saved endpoint takes effect: 1.1.5 defaults to
 * Blockstream's Esplora and restarts a running node as soon as the endpoint changes.
 */
class EsploraEndpointTest {

    @Test
    fun mainnetDefaultsToBlockstream() {
        assertEquals("https://blockstream.info/api", ChainNetwork.MAINNET.defaultEsploraUrl)
        assertTrue(EsploraUrl.isValid(ChainNetwork.MAINNET.defaultEsploraUrl))
    }

    @Test
    fun everyNetworkHasAPlainHttpsDefault() {
        ChainNetwork.values().forEach { network ->
            val default = network.defaultEsploraUrl
            assertTrue("${network.id}: $default", default.startsWith("https://"))
            assertTrue("${network.id}: $default", EsploraUrl.isValid(default))
        }
    }

    @Test
    fun aChangedEndpointOnARunningNodeIsRestarted() {
        assertTrue(
            esploraChangeNeedsRestart(
                before = "https://blockstream.info/api",
                after = "https://mempool.space/api",
                nodeRunning = true,
                seedAvailable = true
            )
        )
    }

    @Test
    fun savingTheSameEndpointRestartsNothing() {
        assertFalse(
            esploraChangeNeedsRestart(
                before = "https://blockstream.info/api",
                after = "https://blockstream.info/api",
                nodeRunning = true,
                seedAvailable = true
            )
        )
    }

    @Test
    fun aStoppedNodeHasNothingToRestart() {
        assertFalse(
            esploraChangeNeedsRestart(
                before = "https://blockstream.info/api",
                after = "http://127.0.0.1:3002",
                nodeRunning = false,
                seedAvailable = true
            )
        )
    }

    @Test
    fun aDeviceWithoutARecoveryPhraseHasNothingToRestart() {
        assertFalse(
            esploraChangeNeedsRestart(
                before = "https://blockstream.info/api",
                after = "https://example.com/esplora",
                nodeRunning = true,
                seedAvailable = false
            )
        )
    }
}
