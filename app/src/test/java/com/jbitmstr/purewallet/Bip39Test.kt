package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Bip39
import com.jbitmstr.purewallet.core.Bip39Wordlist
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
    fun randomTwentyFourWordMnemonicIsValid() {
        val mnemonic = Bip39.randomMnemonic(24)
        assertTrue(Bip39.isValidMnemonic(mnemonic))
        assertEquals(24, Bip39.wordCount(mnemonic))
    }

    @Test
    fun everySupportedWordCountProducesAValidPhrase() {
        for (count in listOf(12, 15, 18, 21, 24)) {
            val mnemonic = Bip39.randomMnemonic(count)
            assertEquals(count, Bip39.wordCount(mnemonic))
            assertTrue(Bip39.isValidMnemonic(mnemonic))
        }
    }

    @Test
    fun twentyFourWordPhraseRoundTripsToThirtyTwoBytes() {
        val mnemonic = Bip39.randomMnemonic(24)
        assertEquals(32, Bip39.entropyFromMnemonic(mnemonic).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedWordCountIsRejected() {
        Bip39.randomMnemonic(13)
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
    fun suggestWordsMatchesAPrefixInWordlistOrder() {
        assertEquals(listOf("abandon"), Bip39.suggestWords("aban"))
        assertEquals(listOf("abandon"), Bip39.suggestWords("ABANDON"))
        assertEquals(listOf("abandon"), Bip39.suggestWords("  abando  "))
        assertEquals(
            listOf("absent", "absorb", "abstract", "absurd"),
            Bip39.suggestWords("abs")
        )
    }

    @Test
    fun suggestWordsStaysWithinTheLimit() {
        assertEquals(listOf("abandon", "ability", "able"), Bip39.suggestWords("ab", limit = 3))
        val default = Bip39.suggestWords("a")
        assertEquals(6, default.size)
        assertTrue(default.all { it.startsWith("a") })
        assertEquals(default, default.sorted())
    }

    @Test
    fun fourLetterPrefixIdentifiesEveryWordUniquely() {
        for (word in Bip39Wordlist.ENGLISH) {
            val prefix = word.take(4)
            val suggestions = Bip39.suggestWords(prefix, limit = 6)
            if (prefix.length == 4) {
                assertEquals("prefix \"$prefix\" should only match \"$word\"", listOf(word), suggestions)
            } else {
                // A three-letter word is also the prefix of longer words such as act -> action.
                assertEquals(word, suggestions.first())
            }
        }
    }

    @Test
    fun wordlistMembershipIsExact() {
        assertTrue(Bip39.isWord("abandon"))
        assertTrue(Bip39.isWord("zoo"))
        assertFalse(Bip39.isWord("aban"))
        assertFalse(Bip39.isWord("Abandon"))
        assertFalse(Bip39.isWord(""))
    }

    @Test
    fun everyWordIsReachableFromItsFullText() {
        for (word in listOf("abandon", "acid", "zoo", "wrong", "yellow")) {
            assertEquals(listOf(word), Bip39.suggestWords(word))
        }
    }

    @Test
    fun invalidOrEmptyPrefixReturnsNothing() {
        assertTrue(Bip39.suggestWords("").isEmpty())
        assertTrue(Bip39.suggestWords("   ").isEmpty())
        assertTrue(Bip39.suggestWords("zzzz").isEmpty())
        assertTrue(Bip39.suggestWords("x").isEmpty())
        assertTrue(Bip39.suggestWords("abc1").isEmpty())
        assertTrue(Bip39.suggestWords("ab-an").isEmpty())
        assertTrue(Bip39.suggestWords("abandon }").isEmpty())
    }

    @Test
    fun nonPositiveLimitReturnsNothing() {
        assertTrue(Bip39.suggestWords("ab", limit = 0).isEmpty())
        assertTrue(Bip39.suggestWords("ab", limit = -3).isEmpty())
    }

    @Test
    fun currentWordIsTheHalfTypedLastToken() {
        assertEquals("", Bip39.currentWord(""))
        assertEquals("", Bip39.currentWord("abandon "))
        assertEquals("ab", Bip39.currentWord("abandon ab"))
        assertEquals("ab", Bip39.currentWord("abandon\n  ab"))
        assertEquals("AB", Bip39.currentWord("abandon AB").uppercase())
    }

    @Test
    fun pickingASuggestionFinishesTheWordAndLeavesATrailingSpace() {
        assertEquals("abandon ", Bip39.withCompletedWord("", "abandon"))
        assertEquals("abandon abandon ", Bip39.withCompletedWord("abandon aban", "abandon"))
        assertEquals("zoo zoo wrong ", Bip39.withCompletedWord("zoo zoo wro", "wrong"))
        assertEquals("abandon\nzoo ", Bip39.withCompletedWord("abandon\nzo", "zoo"))
    }

    @Test
    fun completedWordPhraseStaysValid() {
        var text = ""
        val words = Bip39.mnemonicFromEntropy(ByteArray(16)).split(" ")
        for (word in words) {
            text = Bip39.withCompletedWord(text, word)
        }
        assertTrue(Bip39.isValidMnemonic(text))
        assertEquals(12, Bip39.wordCount(text))
    }

    @Test
    fun pastedPhraseOffersNothingOnceItEndsWithWhitespace() {
        val phrase = Bip39.randomMnemonic(24)
        assertTrue(Bip39.isValidMnemonic(phrase))
        assertEquals("", Bip39.currentWord("$phrase\n"))
        assertTrue(Bip39.suggestWords(Bip39.currentWord("$phrase\n")).isEmpty())
        assertEquals("ab", Bip39.currentWord("$phrase ab"))
    }

    @Test
    fun whitespaceAndCaseAreNormalized() {
        val valid = Bip39.mnemonicFromEntropy(ByteArray(16))
        val messy = "  " + valid.uppercase().replace(" ", "\n") + "  "
        assertEquals(valid, Bip39.normalize(messy))
        assertTrue(Bip39.isValidMnemonic(messy))
    }
}
