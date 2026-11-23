package com.corejbh.zerowallet

import com.corejbh.zerowallet.core.Bolt12Chain
import com.corejbh.zerowallet.core.ChainNetwork
import com.corejbh.zerowallet.core.SeedChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The two checks that keep a BOLT12 payment on the right chain and the backup confirmation
 * from asking a known pair of words.
 */
class Bolt12AndSeedChallengeTest {

    // ------------------------------------------------------------ BOLT12 chain

    private fun hashBytes(hex: String): List<UByte> =
        hex.chunked(2).map { it.toInt(16).toUByte() }

    @Test
    fun anInvoiceOnThisChainMatches() {
        for (network in ChainNetwork.values()) {
            assertTrue(
                "${network.id} should match its own genesis hash",
                Bolt12Chain.matches(hashBytes(network.genesisBlockHash), network)
            )
        }
    }

    @Test
    fun anInvoiceFromAnotherChainDoesNotMatch() {
        val signetGenesis = hashBytes(ChainNetwork.SIGNET.genesisBlockHash)
        assertFalse(Bolt12Chain.matches(signetGenesis, ChainNetwork.MAINNET))
        assertFalse(Bolt12Chain.matches(signetGenesis, ChainNetwork.TESTNET))
        val mainnetGenesis = hashBytes(ChainNetwork.MAINNET.genesisBlockHash)
        assertFalse(Bolt12Chain.matches(mainnetGenesis, ChainNetwork.SIGNET))
    }

    @Test
    fun theGenesisHashesAreDistinctAndWellFormed() {
        val hashes = ChainNetwork.values().map { it.genesisBlockHash }
        assertEquals(hashes.size, hashes.toSet().size)
        for (hash in hashes) {
            assertEquals(64, hash.length)
            assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test
    fun aChainFieldThatIsNotAHashIsRefused() {
        assertNull(Bolt12Chain.hexOf(emptyList()))
        assertNull(Bolt12Chain.hexOf(List(31) { 0u.toUByte() }))
        assertNull(Bolt12Chain.hexOf(List(33) { 0u.toUByte() }))
        assertFalse(Bolt12Chain.matches(emptyList(), ChainNetwork.MAINNET))
    }

    @Test
    fun theChainIsRenderedAsLowercaseHex() {
        val bytes = hashBytes("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f")
        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            Bolt12Chain.hexOf(bytes)
        )
    }

    // ---------------------------------------------------------- seed challenge

    @Test
    fun theTwoWordPositionsAreAlwaysDifferentAndInRange() {
        val random = Random(7)
        for (size in intArrayOf(2, 3, 8, 12, 24, 25)) {
            repeat(200) {
                val indices = SeedChallenge.indices(size, random)
                assertEquals(2, indices.size)
                assertTrue(indices.all { it in 0 until size })
                assertNotEquals(indices[0], indices[1])
            }
        }
    }

    @Test
    fun everyPositionCanBeAskedAndTheAnswerIsNotFixed() {
        val asked = mutableSetOf<Int>()
        val random = Random(11)
        repeat(500) {
            val indices = SeedChallenge.indices(12, random)
            asked.add(indices[0])
            asked.add(indices[1])
        }
        // All twelve words come up; a fixed pair would only ever produce two of them.
        assertEquals((0 until 12).toSet(), asked)
    }

    @Test
    fun aSingleWordPhraseStillProducesTwoAnswers() {
        assertEquals(listOf(0, 0), SeedChallenge.indices(1))
        assertEquals(listOf(0, 0), SeedChallenge.indices(0))
    }
}
