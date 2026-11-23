package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.ChainNetwork
import com.corejbh.zerowallet.core.GossipSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which gossip snapshot a node is built with, per chain.
 *
 * Regression cover for the eighth review: one mainnet RGS URL was used on every chain, so a
 * signet or testnet node downloaded the mainnet channel graph - a picture of a network it is
 * not on. RGS is served per chain, and a chain with no server of its own is left with
 * peer-to-peer gossip instead of borrowing another chain's.
 */
class GossipSourceTest {

    @Test
    fun mainnetUsesTheMainnetSnapshot() {
        val url = GossipSource.rgsUrlFor(ChainNetwork.MAINNET, rgsEnabled = true)
        assertEquals("https://rapidsync.lightningdevkit.org/snapshot/", url)
    }

    @Test
    fun testnetUsesTheTestnetSnapshot() {
        val url = GossipSource.rgsUrlFor(ChainNetwork.TESTNET, rgsEnabled = true)
        assertEquals("https://rapidsync.lightningdevkit.org/testnet/snapshot/", url)
    }

    @Test
    fun signetHasNoSnapshotAndFallsBackToPeers() {
        // The LDK snapshot server answers 404 for signet; there is no graph to fetch, and the
        // mainnet one must never be used in its place.
        assertNull(ChainNetwork.SIGNET.rgsUrl)
        assertFalse(ChainNetwork.SIGNET.rgsSupported)
        assertNull(GossipSource.rgsUrlFor(ChainNetwork.SIGNET, rgsEnabled = true))
    }

    @Test
    fun noChainBorrowsAnotherChainsUrl() {
        val urls = ChainNetwork.values().mapNotNull { it.rgsUrl }
        assertEquals(urls.size, urls.distinct().size)
        // The mainnet path is the bare one: any other chain's URL must carry its own prefix.
        ChainNetwork.values().filter { !it.isMainnet }.forEach { chain ->
            val url = chain.rgsUrl ?: return@forEach
            assertTrue("${chain.id} uses the mainnet path: $url", url != ChainNetwork.MAINNET.rgsUrl)
        }
    }

    @Test
    fun aDisabledSwitchMeansPeersOnEveryChain() {
        ChainNetwork.values().forEach { chain ->
            assertNull(GossipSource.rgsUrlFor(chain, rgsEnabled = false))
        }
    }

    @Test
    fun theChainsThatHaveAServerSaySo() {
        assertTrue(ChainNetwork.MAINNET.rgsSupported)
        assertTrue(ChainNetwork.TESTNET.rgsSupported)
        assertNotNull(ChainNetwork.MAINNET.rgsUrl)
        // A supported chain's URL is https, like every other network call this wallet makes.
        ChainNetwork.values().mapNotNull { it.rgsUrl }.forEach {
            assertTrue(it, it.startsWith("https://"))
        }
    }
}
