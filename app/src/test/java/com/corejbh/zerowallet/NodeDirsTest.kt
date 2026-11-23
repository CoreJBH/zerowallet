package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.NodeDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder names a channel-backup import leaves behind.
 *
 * The tenth review found the store could be left one rename away from its place with nothing
 * to put it back: an import moves the live store to `previous-*` and then renames the backup
 * into place, and a process killed in between made the next start create an empty node - a
 * wallet that looks wiped while its channels are on disk. The names are what lets the next
 * start find the store again, and they carry the chain because every chain shares `ldk-node/`.
 */
class NodeDirsTest {

    @Test
    fun anImportNamesItsFoldersAfterTheChainItIsOn() {
        assertEquals("incoming-mainnet-1763000000000", NodeDirs.incoming("mainnet", 1_763_000_000_000L))
        assertEquals("previous-mainnet-1763000000000", NodeDirs.previous("mainnet", 1_763_000_000_000L))
        assertEquals("incoming-signet-5", NodeDirs.incoming("signet", 5L))
    }

    @Test
    fun theNewestDisplacedStoreIsTheOneThatComesBack() {
        val names = listOf(
            "previous-mainnet-100",
            "previous-mainnet-300",
            "previous-mainnet-200",
            "mainnet",
            "logs"
        )
        assertEquals("previous-mainnet-300", NodeDirs.newestPrevious(names, "mainnet"))
    }

    @Test
    fun anotherChainsStoreIsNeverRestoredIntoThisOne() {
        val names = listOf("previous-signet-999", "previous-testnet-999")
        assertNull(NodeDirs.newestPrevious(names, "mainnet"))
    }

    @Test
    fun nothingToRestoreIsNull() {
        assertNull(NodeDirs.newestPrevious(emptyList(), "mainnet"))
        assertNull(NodeDirs.newestPrevious(listOf("mainnet", "logs", "incoming-mainnet-1"), "mainnet"))
    }

    @Test
    fun incomingFoldersAreRecognisedPerChain() {
        assertTrue(NodeDirs.isIncoming("incoming-mainnet-1763000000000", "mainnet"))
        assertFalse(NodeDirs.isIncoming("incoming-signet-1763000000000", "mainnet"))
        assertFalse(NodeDirs.isIncoming("previous-mainnet-1763000000000", "mainnet"))
        assertFalse(NodeDirs.isIncoming("mainnet", "mainnet"))
    }

    @Test
    fun everyHalfWrittenImportGoesWhicheverChainItWasFor() {
        // A folder an import was writing into never became the wallet, so nothing is lost by
        // clearing it - including one left on a chain that is not open now.
        val names = listOf("incoming-mainnet-1", "incoming-signet-2", "mainnet", "previous-mainnet-3")
        assertEquals(listOf("incoming-mainnet-1", "incoming-signet-2"), NodeDirs.incomingDirs(names))
    }

    @Test
    fun previousAndIncomingAreNotConfused() {
        assertTrue(NodeDirs.isPrevious("previous-mainnet-1", "mainnet"))
        assertFalse(NodeDirs.isPrevious("incoming-mainnet-1", "mainnet"))
        // A chain whose id is a prefix of another's must not match it.
        assertFalse(NodeDirs.isPrevious("previous-mainnet2-1", "mainnet"))
    }
}
