package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.Bip39
import com.jbitmstr.purewallet.core.ChannelBackup
import com.jbitmstr.purewallet.core.ChannelBackupReview
import com.jbitmstr.purewallet.core.BackupManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file's own format, and what an old one means for the user.
 *
 * The tenth review asked for the restore confirmation to say when a backup came from an older
 * format: its key is derived more cheaply than this build derives one, so the file should be
 * replaced once it has been restored. The version byte is the only thing that says so, and it
 * has to be read from the header without decrypting anything (a wrong phrase must still yield
 * a readable "this is an old format" line, not a decryption failure first).
 */
class ChannelBackupFormatTest {

    private val phrase = Bip39.randomMnemonic(12)

    private fun manifest(channels: Int = 1) = BackupManifest(
        network = "mainnet",
        createdAtEpochSecs = 1_763_000_000L,
        nodeId = "02" + "ab".repeat(32),
        channelCount = channels,
        appVersion = "1.1.18",
        wordCount = 12
    )

    private val files = listOf(
        com.jbitmstr.purewallet.core.BackupFile("channel_manager", byteArrayOf(1, 2, 3))
    )

    @Test
    fun aFreshBackupIsFormatThree() {
        val sealed = ChannelBackup.encrypt(phrase, ChannelBackup.buildArchive(manifest(), files))
        assertEquals(ChannelBackup.FORMAT_VERSION, ChannelBackup.formatOf(sealed))
    }

    @Test
    fun theHeaderOfAForeignFileNamesNoFormat() {
        assertNull(ChannelBackup.formatOf(byteArrayOf(1, 2, 3)))
        assertNull(ChannelBackup.formatOf(ByteArray(64) { 0x5A }))
    }

    @Test
    fun anOlderFormatIsCalledOutWithWhatToDoAboutIt() {
        val review = ChannelBackupReview.review(
            manifest = manifest(),
            networkId = "mainnet",
            nodeId = "02" + "ab".repeat(32),
            nowEpochSecs = 1_763_000_000L,
            formatVersion = ChannelBackup.LEGACY_FORMAT_VERSION
        )
        val warning = review.warnings.firstOrNull { it.contains("older format") }
        assertTrue("an old format has to be named", warning != null)
        assertTrue("it names the version", warning!!.contains("v1"))
        assertTrue("and asks for a new backup", warning.contains("new channel backup"))
        // It is a warning, not a refusal: the file still opens and still restores.
        assertTrue(review.canRestore)
    }

    @Test
    fun formatTwoIsCalledOutAsWell() {
        val review = ChannelBackupReview.review(
            manifest = manifest(),
            networkId = "mainnet",
            nodeId = "",
            nowEpochSecs = 1_763_000_000L,
            formatVersion = ChannelBackup.HKDF_FORMAT_VERSION
        )
        assertTrue(review.warnings.any { it.contains("v2") })
    }

    @Test
    fun theCurrentFormatIsNotWarnedAbout() {
        val review = ChannelBackupReview.review(
            manifest = manifest(),
            networkId = "mainnet",
            nodeId = "",
            nowEpochSecs = 1_763_000_000L,
            formatVersion = ChannelBackup.FORMAT_VERSION
        )
        assertFalse(review.warnings.any { it.contains("older format") })
    }

    @Test
    fun anUnknownVersionSaysNothingItCannotKnow() {
        // The caller that did not read the header (or a file it could not read) must not
        // produce a claim about a format.
        val review = ChannelBackupReview.review(
            manifest = manifest(),
            networkId = "mainnet",
            nodeId = "",
            nowEpochSecs = 1_763_000_000L,
            formatVersion = null
        )
        assertFalse(review.warnings.any { it.contains("older format") })
    }
}
