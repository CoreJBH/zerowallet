package com.jbitmstr.purewallet.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-39 mnemonic generation and checking.
 *
 * Entropy comes from the platform CSPRNG ([randomMnemonic]: 128 bits for 12 words and
 * 256 bits for 24 words from SecureRandom, the same source Android uses for key material).
 * The entropy buffer is zeroed as soon as the words exist, so it does not linger in the
 * heap. Mnemonics can also be parsed back and validated for restore.
 *
 * Everything here is deterministic and free of Android APIs so it can be covered by
 * plain JVM unit tests.
 */
object Bip39 {

    private val WORDLIST = Bip39Wordlist.ENGLISH
    private val WORD_INDEX: Map<String, Int> =
        WORDLIST.withIndex().associate { (index, word) -> word to index }

    private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)
    private val VALID_ENTROPY_SIZES = setOf(16, 20, 24, 28, 32)
    private val LAST_TOKEN = Regex("(\\S+)\\z")

    /** Collapses whitespace, trims and lowercases, so pasted seeds compare reliably. */
    fun normalize(mnemonic: String): String =
        mnemonic.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Number of words in a mnemonic, or 0 when it is empty. */
    fun wordCount(mnemonic: String): Int {
        val normalized = normalize(mnemonic)
        return if (normalized.isEmpty()) 0 else normalized.split(" ").size
    }

    /**
     * A fresh mnemonic from the platform CSPRNG: 12 words (128 bits) by default, or any
     * supported count such as 24 words (256 bits).
     */
    fun randomMnemonic(wordCount: Int = 12): String {
        require(wordCount in VALID_WORD_COUNTS) { "Unsupported word count: $wordCount" }
        val entropyBytes = wordCount * 32 / 3 / 8
        val entropy = ByteArray(entropyBytes)
        try {
            SecureRandom().nextBytes(entropy)
            return mnemonicFromEntropy(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /** Encodes entropy (16/20/24/28/32 bytes) as a BIP-39 mnemonic with checksum. */
    fun mnemonicFromEntropy(entropy: ByteArray): String {
        require(entropy.size in VALID_ENTROPY_SIZES) { "Unsupported entropy size: ${entropy.size}" }
        val checksumBitCount = entropy.size * 8 / 32
        val checksum = sha256(entropy)

        val totalBits = entropy.size * 8 + checksumBitCount
        val wordTotal = totalBits / 11

        val indexes = IntArray(wordTotal)
        var bitPosition = 0
        for (word in 0 until wordTotal) {
            var index = 0
            for (bit in 0 until 11) {
                index = (index shl 1) or bitAt(entropy, checksum, bitPosition)
                bitPosition++
            }
            indexes[word] = index
        }
        return indexes.joinToString(" ") { WORDLIST[it] }
    }

    /**
     * Decodes a mnemonic back to its entropy, verifying the BIP-39 checksum.
     *
     * @throws IllegalArgumentException when a word is unknown, the word count is invalid
     * or the checksum does not match.
     */
    fun entropyFromMnemonic(mnemonic: String): ByteArray {
        val normalized = normalize(mnemonic)
        require(normalized.isNotEmpty()) { "Mnemonic is empty" }
        val words = normalized.split(" ")
        require(words.size in VALID_WORD_COUNTS) { "Unsupported word count: ${words.size}" }

        val indexes = IntArray(words.size)
        for (i in words.indices) {
            indexes[i] = WORD_INDEX[words[i]] ?: throw IllegalArgumentException(
                "Word ${i + 1} is not in the BIP-39 English wordlist: ${words[i]}"
            )
        }

        val totalBits = words.size * 11
        val entropyBits = totalBits * 32 / 33
        val entropy = ByteArray(entropyBits / 8)

        var bitPosition = 0
        for (index in indexes) {
            for (bit in 10 downTo 0) {
                val value = (index shr bit) and 1
                if (bitPosition < entropyBits && value == 1) {
                    val byteIndex = bitPosition / 8
                    val bitInByte = 7 - (bitPosition % 8)
                    entropy[byteIndex] = (entropy[byteIndex].toInt() or (1 shl bitInByte)).toByte()
                }
                bitPosition++
            }
        }

        val reencoded = mnemonicFromEntropy(entropy)
        require(reencoded == normalized) { "Invalid BIP-39 checksum" }
        return entropy
    }

    /** True when the mnemonic has a known word count, known words and a valid checksum. */
    fun isValidMnemonic(mnemonic: String): Boolean =
        runCatching { entropyFromMnemonic(mnemonic) }.isSuccess

    /** Returns the first word that is not part of the wordlist, or null. */
    fun firstUnknownWord(mnemonic: String): String? =
        normalize(mnemonic).split(" ").firstOrNull { it.isNotEmpty() && !WORD_INDEX.containsKey(it) }

    /**
     * The word currently being typed: the last whitespace-separated token of [text], lowercased.
     * Returns an empty string when the text is blank or ends with whitespace, because at that
     * point no word is half-typed.
     */
    fun currentWord(text: String): String =
        LAST_TOKEN.find(text)?.groupValues?.get(1)?.lowercase() ?: ""

    /**
     * BIP-39 wordlist entries that start with [prefix], in wordlist (alphabetical) order.
     *
     * The match is case-insensitive and the prefix is trimmed. An empty prefix, a prefix with
     * characters outside `a`-`z`, or one no word starts with returns an empty list. Four letters
     * already identify a single word in the English list, so a helper can complete it with one tap.
     *
     * @param limit maximum number of suggestions to return.
     */
    fun suggestWords(prefix: String, limit: Int = 6): List<String> {
        val cleaned = prefix.trim().lowercase()
        if (cleaned.isEmpty() || limit <= 0) return emptyList()
        if (cleaned.any { it < 'a' || it > 'z' }) return emptyList()
        val matches = ArrayList<String>(limit)
        for (word in WORDLIST) {
            if (word.startsWith(cleaned)) {
                matches.add(word)
                if (matches.size == limit) break
            }
        }
        return matches
    }

    /**
     * Replaces the half-typed last word of [text] with the complete [word], keeping everything the
     * user typed before it, and leaves a trailing space so the next word can be typed right away.
     */
    fun withCompletedWord(text: String, word: String): String {
        if (word.isEmpty()) return text
        val match = LAST_TOKEN.find(text)
        return if (match == null) {
            "$text$word "
        } else {
            text.substring(0, match.range.first) + word + " "
        }
    }

    private fun bitAt(entropy: ByteArray, checksum: ByteArray, position: Int): Int {
        val byteIndex = position / 8
        val bitInByte = 7 - (position % 8)
        val source = if (byteIndex < entropy.size) {
            entropy[byteIndex]
        } else {
            checksum[byteIndex - entropy.size]
        }
        return (source.toInt() shr bitInByte) and 1
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
