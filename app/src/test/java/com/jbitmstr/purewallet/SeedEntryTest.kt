package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Bip39
import com.jbitmstr.purewallet.core.Bip39Wordlist
import com.jbitmstr.purewallet.core.SeedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the restore grid's rules: what a box accepts, when a half-typed word finishes itself
 * and jumps, when it must not, and how a pasted phrase spreads over the boxes.
 */
class SeedEntryTest {

    private fun emptyGrid(): List<String> = List(SeedEntry.SLOTS) { "" }

    private fun grid(vararg words: String): List<String> = emptyGrid().toMutableList().also {
        words.forEachIndexed { index, word -> it[index] = word }
    }

    @Test
    fun boxKeepsOnlyLowercaseLettersWithinTheLongestWord() {
        assertEquals("abandon", SeedEntry.typedWord("Abandon"))
        assertEquals("abandon", SeedEntry.typedWord(" ABANDON "))
        assertEquals("abandon", SeedEntry.typedWord("1.abandon,"))
        assertEquals("ab", SeedEntry.typedWord("a-b"))
        assertEquals("", SeedEntry.typedWord("123"))
        assertEquals(SeedEntry.MAX_WORD_LENGTH, SeedEntry.typedWord("abcdefghijkl").length)
        assertEquals(8, SeedEntry.MAX_WORD_LENGTH)
    }

    @Test
    fun uniquePrefixCompletesAsSoonAsItNamesOneWord() {
        assertEquals("abandon", SeedEntry.autoCompletion("aba", growing = true))
        assertEquals("abandon", SeedEntry.autoCompletion("aban", growing = true))
        assertEquals("zoo", SeedEntry.autoCompletion("zoo", growing = true))
        assertEquals("zebra", SeedEntry.autoCompletion("zebr", growing = true))
    }

    @Test
    fun shortOrAmbiguousPrefixStaysForTheChips() {
        assertNull(SeedEntry.autoCompletion("ab", growing = true))
        assertNull(SeedEntry.autoCompletion("act", growing = true))
        assertNull(SeedEntry.autoCompletion("abb", growing = true))
        assertNull(SeedEntry.autoCompletion("zzzz", growing = true))
        assertNull(SeedEntry.autoCompletion("abc1", growing = true))
    }

    @Test
    fun deletingNeverSnapsTheBoxShut() {
        assertNull(SeedEntry.autoCompletion("aba", growing = false))
        assertNull(SeedEntry.autoCompletion("abandon", growing = false))
    }

    @Test
    fun typingAUniquePrefixFillsTheBoxAndMovesToTheNextOne() {
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, "aban")
        assertEquals("abandon", edit.words[0])
        assertEquals(1, edit.focus)
        assertEquals(12, edit.wordCount)
    }

    @Test
    fun typingMovesOnFromTheBoxThatIsBeingFilledNotAlwaysTheFirst() {
        val edit = SeedEntry.edit(grid("abandon", "ability"), 12, 2, "zoo")
        assertEquals("zoo", edit.words[2])
        assertEquals(3, edit.focus)
    }

    @Test
    fun partialWordStaysInItsBox() {
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, "ab")
        assertEquals("ab", edit.words[0])
        assertEquals(0, edit.focus)
    }

    @Test
    fun backspacingInsideACompletedWordKeepsTheBoxAndTheCaret() {
        val edit = SeedEntry.edit(grid("abandon"), 12, 0, "abando")
        assertEquals("abando", edit.words[0])
        assertEquals(0, edit.focus)
    }

    @Test
    fun typingAgainAfterABackspaceCompletesTheWord() {
        val edit = SeedEntry.edit(grid("abando"), 12, 0, "abandon")
        assertEquals("abandon", edit.words[0])
        assertEquals(1, edit.focus)
    }

    @Test
    fun typingIntoACompleteBoxStartsThatBoxOver() {
        // "abandon" is finished; the next letter must not turn it into "abandonz".
        val edit = SeedEntry.edit(grid("abandon"), 12, 0, "abandonz")
        assertEquals("z", edit.words[0])
        assertEquals(0, edit.focus)

    }

    @Test
    fun aCompleteWordStillTakesTheLettersOfALongerOne() {
        // "act" is a word on its own, but a letter after it belongs to actor, not to a restart.
        val edit = SeedEntry.edit(grid("act"), 12, 0, "acto")
        assertEquals("actor", edit.words[0])
        assertEquals(1, edit.focus)
    }

    @Test
    fun partialWordGrowsUntilItIsUnique() {
        val ambiguous = SeedEntry.edit(grid("abl"), 12, 0, "able")
        assertEquals("able", ambiguous.words[0])
        assertEquals(1, ambiguous.focus)
    }

    @Test
    fun autoAdvanceNeverLandsOnABoxThatIsAlreadyFilled() {
        val edit = SeedEntry.edit(grid("", "zoo"), 12, 0, "aban")
        assertEquals("abandon", edit.words[0])
        assertEquals(0, edit.focus)
        assertEquals("zoo", edit.words[1])
    }

    @Test
    fun theLastBoxHasNowhereToJump() {
        val edit = SeedEntry.edit(emptyGrid(), 12, 11, "zoo")
        assertEquals("zoo", edit.words[11])
        assertEquals(11, edit.focus)
    }

    @Test
    fun tappingAChipFillsTheBoxAndOpensTheNextOne() {
        val edit = SeedEntry.pick(emptyGrid(), 12, 0, "action")
        assertEquals("action", edit.words[0])
        assertEquals(1, edit.focus)
    }

    @Test
    fun pastingAPhraseSpreadsOverTheBoxesFromTheFirstOne() {
        val phrase = Bip39.randomMnemonic(12).split(" ")
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, phrase.joinToString(" "))
        assertEquals(phrase, edit.words.take(12))
        assertEquals(11, edit.focus)
        assertEquals(12, edit.wordCount)
        assertEquals(phrase.joinToString(" "), SeedEntry.phraseOrNull(edit.words))
    }

    @Test
    fun pastingTwentyFourWordsSwitchesTheGridToTwentyFour() {
        val phrase = Bip39.randomMnemonic(24).split(" ")
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, phrase.joinToString(" "))
        assertEquals(24, edit.wordCount)
        assertEquals(phrase, edit.words)
        assertEquals(23, edit.focus)
        assertEquals(phrase.joinToString(" "), SeedEntry.phraseOrNull(edit.words))
    }

    @Test
    fun pastingAWholePhraseIntoALaterBoxStillStartsAtTheFirstBox() {
        val phrase = Bip39.randomMnemonic(12).split(" ")
        val edit = SeedEntry.edit(grid("zoo"), 12, 4, phrase.joinToString(" "))
        assertEquals(phrase, edit.words.take(12))
        assertEquals(12, edit.wordCount)
        assertEquals(11, edit.focus)
    }

    @Test
    fun pastingIntoALaterBoxContinuesFromThatBoxForAShortRun() {
        val edit = SeedEntry.edit(grid("zoo"), 24, 20, "zebra\nzone")
        assertEquals("zebra", edit.words[20])
        assertEquals("zone", edit.words[21])
        assertEquals(21, edit.focus)
        assertEquals("zoo", edit.words[0])
    }

    @Test
    fun aNumberedPastedListLosesItsNumbersAndPunctuation() {
        val edit = SeedEntry.edit(
            emptyGrid(),
            12,
            0,
            "1. abandon, 2. ability; 3. able\n4. about"
        )
        assertEquals(listOf("abandon", "ability", "able", "about"), edit.words.take(4))
        assertEquals(3, edit.focus)
    }

    @Test
    fun pastingASingleWordBehavesLikeFinishingThatBox() {
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, "aban")
        assertEquals("abandon", edit.words[0])
        assertEquals(1, edit.focus)

        val spaced = SeedEntry.edit(emptyGrid(), 12, 0, "abandon ")
        assertEquals("abandon", spaced.words[0])
        assertEquals(1, spaced.focus)
    }

    @Test
    fun pastingPunctuationOnlyChangesNothing() {
        val edit = SeedEntry.edit(grid("abandon"), 12, 0, " ,, ")
        assertEquals("abandon", edit.words[0])
        assertEquals(0, edit.focus)
    }

    @Test
    fun aPasteLongerThanTheGridKeepsWhatFits() {
        val phrase = List(30) { "abandon" }.joinToString(" ")
        val edit = SeedEntry.edit(emptyGrid(), 12, 0, phrase)
        assertEquals(24, edit.wordCount)
        assertEquals(24, edit.words.count { it == "abandon" })
        assertEquals(23, edit.focus)
        assertTrue(edit.words.all { it == "abandon" })
    }

    @Test
    fun phraseNeedsAtLeastTwelveWordsWithoutAGap() {
        assertNull(SeedEntry.phraseOrNull(emptyGrid()))
        assertNull(SeedEntry.phraseOrNull(grid("abandon", "ability")))
        assertNull(SeedEntry.phraseOrNull(grid(*Array(11) { "abandon" })))
        assertNull(SeedEntry.phraseOrNull(grid("abandon", "", *Array(10) { "abandon" })))
        assertEquals(12, SeedEntry.phraseOrNull(grid(*Array(12) { "abandon" }))?.split(" ")?.size)
    }

    @Test
    fun aPastedFifteenWordPhraseStillFormsAPhrase() {
        val phrase = Bip39.randomMnemonic(24).split(" ").take(15)
        val edit = SeedEntry.edit(emptyGrid(), 24, 0, phrase.joinToString(" "))
        assertEquals(14, edit.focus)
        assertEquals(phrase.joinToString(" "), SeedEntry.phraseOrNull(edit.words))
    }

    @Test
    fun aBoxCanBeEditedAfterAPasteWithoutLosingTheOthers() {
        val phrase = Bip39.randomMnemonic(12).split(" ").toMutableList()
        val first = SeedEntry.edit(emptyGrid(), 12, 0, phrase.joinToString(" "))

        val fixed = SeedEntry.edit(first.words, first.wordCount, 3, "zoo")
        assertEquals("zoo", fixed.words[3])
        phrase[3] = "zoo"
        assertEquals(phrase.joinToString(" "), SeedEntry.phraseOrNull(fixed.words))
    }

    @Test
    fun everyWordCanBeCompletedByTypingThreeOrFourLetters() {
        // The promise of the grid: no word needs more than four letters to be identified,
        // and every 4-letter prefix names exactly one word.
        for (word in Bip39Wordlist.ENGLISH) {
            // Three-letter words are handled by the chips test below: "act" also starts
            // action and actor, so it never completes by itself.
            if (word.length == 3) continue
            val prefix = word.take(4)
            assertTrue(
                "prefix \"$prefix\" of \"$word\" should complete",
                SeedEntry.autoCompletion(prefix, growing = true) == word
            )
            assertFalse(SeedEntry.isPaste(prefix))
        }
    }
}
