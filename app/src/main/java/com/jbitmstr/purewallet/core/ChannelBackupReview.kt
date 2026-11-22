package com.jbitmstr.purewallet.core

/**
 * What an opened channel backup says about itself, and whether it should be allowed to
 * replace the channel data on this phone.
 *
 * The node store is a snapshot, not a Static Channel Backup: see [ChannelBackup] and the
 * README for why. A snapshot restored late is the dangerous case. Every channel state the
 * node has signed since the snapshot is newer than what the file holds, and Lightning's
 * remedy for an out-of-date state is to force-close the channel and let the honest party
 * sweep its balance after a timelock. That is not a theft - the counterparty cannot take the
 * balance it does not own - but it is a channel closed by surprise, for a fee, and with the
 * funds locked for hours or days. A very old state, one from before the channel was last
 * co-signed in a way the counterparty remembers differently, is where a penalty can bite.
 *
 * So the decision is not left to a single "Restore?" tap. This class decides it from the
 * manifest: a backup of another chain or of another wallet is refused outright, and the age
 * is turned into a sentence the user has to see before the button is enabled.
 *
 * Pure, so every rule is unit-tested rather than argued about in the sheet.
 */
data class BackupReview(
    val manifest: BackupManifest,
    /** Whole days between the backup and now, floored at zero. */
    val ageDays: Long,
    /** True when the backup names this wallet's node; null when this phone has no node id yet. */
    val sameNode: Boolean?,
    /** Why this backup must not be restored here. Empty when it may be. */
    val problems: List<String>,
    /** What the user has to be told even when it may be restored. */
    val warnings: List<String>
) {
    /** A restore can only be offered when nothing blocks it. */
    val canRestore: Boolean get() = problems.isEmpty()

    /** A backup that is not from today is worth a second look before it replaces live data. */
    val isStale: Boolean get() = ageDays >= 1
}

object ChannelBackupReview {

    /** Days after which a snapshot is called out as old in the restore confirmation. */
    const val STALE_AFTER_DAYS = 1L

    /**
     * Reads [manifest] against this phone's wallet.
     *
     * @param networkId the chain the wallet is on now.
     * @param nodeId this wallet's node id, or blank when the node has not run here yet.
     * @param nowEpochSecs the current time, so the age is testable.
     * @param formatVersion the file's own format ([ChannelBackup.formatOf]), or null when the
     *   caller did not read it; an older one is called out with what to do about it.
     */
    fun review(
        manifest: BackupManifest,
        networkId: String,
        nodeId: String,
        nowEpochSecs: Long,
        formatVersion: Int? = null
    ): BackupReview {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()

        if (!manifest.network.equals(networkId, ignoreCase = true)) {
            problems += "This backup was saved on ${manifest.network}, and this wallet is on " +
                "$networkId. Switch to ${manifest.network} in Settings first."
        }

        val backupNode = manifest.nodeId.trim()
        val thisNode = nodeId.trim()
        val sameNode = if (backupNode.isEmpty() || thisNode.isEmpty()) {
            null
        } else {
            backupNode.equals(thisNode, ignoreCase = true)
        }
        if (sameNode == false) {
            problems += "This backup belongs to a different wallet (node id " +
                "${Fmt.shortId(backupNode)}). Its channels are not this wallet's, so restoring " +
                "it would not bring them back."
        }

        val ageDays = if (manifest.createdAtEpochSecs <= 0L) {
            0L
        } else {
            ((nowEpochSecs - manifest.createdAtEpochSecs) / 86_400L).coerceAtLeast(0L)
        }

        if (manifest.createdAtEpochSecs <= 0L) {
            warnings += "This file does not say when it was made, so it cannot be checked " +
                "against your channels' latest state."
        } else if (ageDays >= STALE_AFTER_DAYS) {
            warnings += "This backup is $ageDays ${if (ageDays == 1L) "day" else "days"} old. " +
                "The node keeps changing its channel state, and the state in this file is older " +
                "than the latest one every time a payment moves. Restoring an older state makes " +
                "the phone force-close channels that changed since: the funds come back after a " +
                "timelock, minus the closing fee, and an old enough state can cost a penalty."
        }

        val olderFormat = formatVersion?.takeIf { it < ChannelBackup.FORMAT_VERSION }
        if (olderFormat != null) {
            warnings += "This backup was written in an older format (v$olderFormat), so the key " +
                "that opens it comes from a weaker derivation than this wallet uses now. It " +
                "restores the same way, but save a new channel backup right after the restore " +
                "so the file you keep is the current format."
        }

        if (manifest.channelCount <= 0) {
            warnings += "This backup holds no open channels. Restoring it replaces the channel " +
                "data with an empty set."
        }

        warnings += "A channel snapshot is not a Static Channel Backup. ldk-node 0.7.0 keeps " +
            "the full channel state on this phone and offers no SCB export, so a restore is the " +
            "whole store or nothing: the node cannot rebuild one channel's latest state from " +
            "the network."

        return BackupReview(
            manifest = manifest,
            ageDays = ageDays,
            sameNode = sameNode,
            problems = problems,
            warnings = warnings
        )
    }

    /** One line for the Settings screen: when the last backup from this phone was saved. */
    fun ageLabel(savedAtEpochSecs: Long?, nowEpochSecs: Long): String? {
        if (savedAtEpochSecs == null || savedAtEpochSecs <= 0L) return null
        val days = ((nowEpochSecs - savedAtEpochSecs) / 86_400L).coerceAtLeast(0L)
        return when {
            days == 0L -> "today"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }
}
