package com.jbitmstr.purewallet

import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.ChannelBackupReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the restore confirmation decides about a channel backup before it is allowed to
 * replace the channel data on this phone.
 *
 * ldk-node 0.7.0 offers no Static Channel Backup: the node keeps a full channel snapshot in
 * `files/ldk-node/<chain>` and that snapshot, whatever its age, is all a restore gets. The
 * ninth review asked for the risk of restoring an outdated one to be made impossible to miss,
 * so the age and the wallet a file belongs to are turned into blocking problems and plain
 * sentences here rather than being left to the tap of a button.
 */
class ChannelBackupReviewTest {

    private val nodeId = "02" + "ab".repeat(32)
    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun manifest(
        network: String = "mainnet",
        createdAt: Long = now - day,
        node: String = nodeId,
        channels: Int = 2
    ) = BackupManifest(
        network = network,
        createdAtEpochSecs = createdAt,
        nodeId = node,
        channelCount = channels,
        appVersion = "1.1.17",
        wordCount = 12
    )

    private fun review(
        manifest: BackupManifest = manifest(),
        networkId: String = "mainnet",
        currentNodeId: String = nodeId,
        nowEpochSecs: Long = now
    ) = ChannelBackupReview.review(manifest, networkId, currentNodeId, nowEpochSecs)

    @Test
    fun aBackupFromThisWalletOnThisChainMayBeRestored() {
        val result = review()
        assertTrue(result.problems.toString(), result.canRestore)
        assertTrue(result.sameNode == true)
    }

    @Test
    fun aBackupOfAnotherChainIsRefusedWithTheChainToSwitchTo() {
        val result = review(manifest(network = "signet"))
        assertFalse(result.canRestore)
        assertTrue(result.problems.first(), result.problems.first().contains("Switch to signet"))
    }

    @Test
    fun aBackupOfAnotherWalletIsRefused() {
        val result = review(manifest(node = "03" + "cd".repeat(32)))
        assertFalse(result.canRestore)
        assertTrue(result.problems.toString(), result.problems.toString().contains("different wallet"))
    }

    @Test
    fun aWalletWithNoNodeIdYetIsNotCalledADifferentWallet() {
        // A replacement phone that has only just had its phrase restored has no node id to
        // compare against; that is not evidence of another wallet.
        val result = review(currentNodeId = "")
        assertNull(result.sameNode)
        assertTrue(result.canRestore)
    }

    @Test
    fun aBackupFromTodayIsNotCalledStale() {
        val result = review(manifest(createdAt = now - 3_600L))
        assertEquals(0L, result.ageDays)
        assertFalse(result.isStale)
    }

    @Test
    fun anOldBackupSaysHowOldItIsAndWhatRestoringItDoes() {
        val result = review(manifest(createdAt = now - 9 * day))
        assertEquals(9L, result.ageDays)
        assertTrue(result.isStale)
        val text = result.warnings.first()
        assertTrue(text, text.contains("9 days old"))
        assertTrue(text, text.contains("force-close"))
        // A one-day-old file is worth saying out loud too, in the singular.
        val oneDay = review(manifest(createdAt = now - day))
        assertTrue(oneDay.warnings.first(), oneDay.warnings.first().contains("1 day old"))
    }

    @Test
    fun aBackupThatDoesNotSayWhenItWasMadeIsNotTrustedEither() {
        val result = review(manifest(createdAt = 0L))
        assertEquals(0L, result.ageDays)
        assertTrue(result.warnings.first(), result.warnings.first().contains("does not say when"))
    }

    @Test
    fun aFutureDateIsTreatedAsTodayRatherThanANegativeAge() {
        val result = review(manifest(createdAt = now + 5 * day))
        assertEquals(0L, result.ageDays)
    }

    @Test
    fun everyReviewSaysASnapshotIsNotAStaticChannelBackup() {
        val result = review()
        assertTrue(
            result.warnings.toString(),
            result.warnings.any { it.contains("not a Static Channel Backup") }
        )
    }

    @Test
    fun aBackupWithNoChannelsSaysWhatItWillReplaceThemWith() {
        val result = review(manifest(channels = 0))
        assertTrue(result.warnings.toString(), result.warnings.any { it.contains("no open channels") })
        // Still allowed: an empty backup is a legitimate state, and refusing it would block
        // a wallet whose channels were all closed.
        assertTrue(result.canRestore)
    }

    @Test
    fun theAgeLabelReadsLikeASentence() {
        assertEquals("today", ChannelBackupReview.ageLabel(now - 60L, now))
        assertEquals("yesterday", ChannelBackupReview.ageLabel(now - day, now))
        assertEquals("4 days ago", ChannelBackupReview.ageLabel(now - 4 * day, now))
        assertNull(ChannelBackupReview.ageLabel(null, now))
        assertNull(ChannelBackupReview.ageLabel(0L, now))
    }
}
