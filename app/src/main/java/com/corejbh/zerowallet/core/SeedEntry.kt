package com.corejbh.zerowallet.core

/**
 * Restore-phrase entry: one numbered box per word instead of a single long text field.
 *
 * Everything here is plain Kotlin, so the typing rules can be unit tested on the JVM: which
 * letters a box keeps, when a half-typed word already names exactly one BIP-39 word and can
 * fill itself and jump to the next box, and how a pasted phrase spreads over the grid.
 */
object SeedEntry {

    /** The grid always offers room for a 24-word phrase; the toggle decides how many show. */
    const val SLOTS = 24

    /** The shortest phrase the entry accepts, and the point where the grid switches to 24. */
    const val MIN_WORDS = 12

    /** The longest word in the BIP-39 English list has eight letters ("abstract"). */
    const val MAX_WORD_LENGTH = 8

    /** Three letters are the shortest prefix that can already name a single word. */
    const val AUTO_COMPLETE_PREFIX = 3

    private val NOT_LETTERS = Regex("[^a-z]+")

    /**
     * The grid after one edit: the words in the boxes, the box that should hold the caret and
     * the phrase length the toggle should show.
     */
    data class Edit(
        val words: List<String>,
        val focus: Int,
        val wordCount: Int
    )

    /** The letters a word box keeps: lowercase `a`-`z`, at most [MAX_WORD_LENGTH]. */
    fun typedWord(raw: String): String =
        raw.lowercase().filter { it in 'a'..'z' }.take(MAX_WORD_LENGTH)

    /**
     * The word that finishes [typed] on its own, or null while the box should stay as typed.
     *
     * Three letters are enough when they name exactly one word in the list ("aba" is abandon,
     * "zoo" is zoo). A prefix shared by several words ("act": act, action, actor ...) waits for
     * the user to pick a chip. [growing] is false while the user deletes, so backspacing through
     * a unique prefix does not snap the box shut and throw the caret into the next box.
     */
    fun autoCompletion(typed: String, growing: Boolean): String? {
        if (!growing || typed.length < AUTO_COMPLETE_PREFIX) return null
        if (typed.any { it < 'a' || it > 'z' }) return null
        val matches = Bip39.suggestWords(typed, limit = 2)
        return if (matches.size == 1) matches.first() else null
    }

    /**
     * Splits pasted text into words, accepting what paper backups and other wallets produce:
     * spaces, newlines, commas and a numbered list such as "1. abandon 2. ability". Anything
     * that is not a run of letters counts as a separator.
     */
    fun splitPasted(text: String): List<String> =
        text.lowercase().split(NOT_LETTERS).filter { it.isNotEmpty() }

    /** True when [text] carries a separator, so it arrived as a paste rather than one key. */
    fun isPaste(text: String): Boolean = text.any { it !in 'a'..'z' && it !in 'A'..'Z' }

    /**
     * Applies what just arrived in box [index].
     *
     * - Text with a separator is a paste: its words fill the boxes from [index], or from box 1
     *   when a whole phrase lands in a later box, and a paste longer than 12 words switches the
     *   grid to 24 so all of it is visible.
     * - Otherwise the box takes the letters typed so far and finishes itself when they name a
     *   single word, moving on to the next empty box.
     */
    fun edit(grid: List<String>, wordCount: Int, index: Int, new: String): Edit {
        val slots = fit(grid)
        val box = index.coerceIn(0, wordCount - 1)
        if (isPaste(new)) {
            val pasted = splitPasted(new)
            // Punctuation only: nothing to place, keep the box as it was.
            if (pasted.isEmpty()) return Edit(slots, box, wordCount)
            if (pasted.size == 1) return place(slots, wordCount, box, typedWord(pasted[0]), slots[box])

            val count = if (pasted.size > MIN_WORDS) 24 else wordCount
            val start = if (pasted.size >= MIN_WORDS && box > 0) 0 else box
            val filled = slots.toMutableList()
            val placed = pasted.take(count - start)
            placed.forEachIndexed { offset, word -> filled[start + offset] = typedWord(word) }
            val last = (start + placed.size - 1).coerceAtLeast(0)
            return Edit(filled, last, count)
        }
        val current = slots[box]
        val typed = typedWord(new)
        // A letter typed into a box that already holds a whole word starts that box over
        // ("abandon" + "z" -> "z") instead of gluing itself to the old word, unless the longer
        // text is still the beginning of some word ("act" + "i" keeps acti for action).
        val startingOver = current.isNotEmpty() && Bip39.isWord(current) &&
            typed.length > current.length && typed.startsWith(current) &&
            Bip39.suggestWords(typed).isEmpty()
        return if (startingOver) {
            place(slots, wordCount, box, typed.removePrefix(current), "")
        } else {
            place(slots, wordCount, box, typed, current)
        }
    }

    /** Fills box [index] with the word the user tapped and moves on to the next empty box. */
    fun pick(grid: List<String>, wordCount: Int, index: Int, word: String): Edit =
        fill(grid, wordCount, index, typedWord(word), advance = true)

    /**
     * The phrase the boxes spell, or null while a box before the last word is still empty or
     * fewer than [MIN_WORDS] words are in place. A 15-, 18- or 21-word phrase that arrives as a
     * paste therefore still counts, even though the toggle offers only 12 and 24.
     */
    fun phraseOrNull(grid: List<String>): String? {
        val last = grid.indexOfLast { it.isNotEmpty() }
        if (last < 0) return null
        val words = grid.subList(0, last + 1)
        if (words.size < MIN_WORDS) return null
        if (words.any { it.isEmpty() }) return null
        return words.joinToString(" ")
    }

    private fun place(
        slots: List<String>,
        wordCount: Int,
        index: Int,
        word: String,
        previous: String
    ): Edit {
        val completed = autoCompletion(word, growing = word.length > previous.length)
        return fill(slots, wordCount, index, completed ?: word, advance = completed != null)
    }

    private fun fill(
        grid: List<String>,
        wordCount: Int,
        index: Int,
        word: String,
        advance: Boolean
    ): Edit {
        val slots = fit(grid).toMutableList()
        slots[index] = word
        val next = if (advance && index + 1 < wordCount && slots[index + 1].isEmpty()) {
            index + 1
        } else {
            index
        }
        return Edit(slots, next, wordCount)
    }

    private fun fit(grid: List<String>): List<String> {
        val slots = ArrayList<String>(SLOTS)
        for (i in 0 until SLOTS) slots.add(grid.getOrElse(i) { "" })
        return slots
    }
}
