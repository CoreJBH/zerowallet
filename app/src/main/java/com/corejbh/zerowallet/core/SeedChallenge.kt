package com.corejbh.zerowallet.core

import java.security.SecureRandom
import java.util.Random

/**
 * Picks the words a user is asked to retype when confirming a backup.
 *
 * Two different positions, drawn at random from the phrase. A fixed pair (the 3rd and 8th
 * word, say) is known to anyone who has seen the app, so it proves only that the words were
 * shown - not that they were written down somewhere else. Drawing from [SecureRandom] keeps
 * the question different on every wallet that is set up.
 *
 * Plain Kotlin, so the rule (two positions, always different, always inside the phrase) is
 * covered by unit tests.
 */
object SeedChallenge {

    /** Two different indices into a phrase of [wordCount] words. */
    fun indices(wordCount: Int, random: Random = SecureRandom()): List<Int> {
        if (wordCount <= 1) return listOf(0, 0)
        val first = random.nextInt(wordCount)
        // Draw from the remaining positions so the two are never the same word.
        var second = random.nextInt(wordCount - 1)
        if (second >= first) second++
        return listOf(first, second)
    }
}
