package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Bip39
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the BIP-39 implementation against the official BIP-39 test vectors plus
 * round-trip, normalization and rejection cases.
 */
class Bip39Test {

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun officialVectorAllZeroEntropy() {
        val mnemonic = Bip39.mnemonicFromEntropy(ByteArray(16))
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            mnemonic
        )
    }

    @Test
    fun officialVectorAllOnesEntropy() {
        val entropy = hexToBytes("ffffffffffffffffffffffffffffffff")
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            Bip39.mnemonicFromEntropy(entropy)
        )
    }

    @Test
    fun officialVectorSevenF() {
        val entropy = hexToBytes("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            Bip39.mnemonicFromEntropy(entropy)
        )
    }

    @Test
    fun officialVectorTwentyFourWords() {
        val entropy = hexToBytes("0000000000000000000000000000000000000000000000000000000000000000")
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            Bip39.mnemonicFromEntropy(entropy)
        )
    }

    @Test
    fun entropyRoundTrip() {
        val entropy = hexToBytes("d02b7d84b93c92d8f2ad4682957b9d2c")
        val mnemonic = Bip39.mnemonicFromEntropy(entropy)
        assertArrayEqualsHex(entropy, Bip39.entropyFromMnemonic(mnemonic))
    }

    private fun assertArrayEqualsHex(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.joinToString("") { "%02x".format(it) }, actual.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun randomMnemonicIsValidAndTwelveWords() {
        val mnemonic = Bip39.randomMnemonic(12)
        assertTrue(Bip39.isValidMnemonic(mnemonic))
        assertEquals(12, Bip39.wordCount(mnemonic))
    }

    @Test
    fun randomMnemonicsDiffer() {
        assertFalse(Bip39.randomMnemonic() == Bip39.randomMnemonic())
    }

    @Test
    fun checksumErrorsAreRejected() {
        val valid = Bip39.mnemonicFromEntropy(ByteArray(16))
        val words = valid.split(" ")
        val broken = (words.dropLast(1) + "abandon").joinToString(" ")
        assertFalse(Bip39.isValidMnemonic(broken))
    }

    @Test
    fun unknownWordIsRejected() {
        val words = Bip39.mnemonicFromEntropy(ByteArray(16) + ByteArray(0)).split(" ").toMutableList()
        words[0] = "notaword"
        assertFalse(Bip39.isValidMnemonic(words.joinToString(" ")))
        assertNotNull(Bip39.firstUnknownWord(words.joinToString(" ")))
        assertEquals("notaword", Bip39.firstUnknownWord(words.joinToString(" ")))
    }

    @Test
    fun whitespaceAndCaseAreNormalized() {
        val valid = Bip39.mnemonicFromEntropy(ByteArray(16))
        val messy = "  " + valid.uppercase().replace(" ", "\n") + "  "
        assertEquals(valid, Bip39.normalize(messy))
        assertTrue(Bip39.isValidMnemonic(messy))
    }
}
