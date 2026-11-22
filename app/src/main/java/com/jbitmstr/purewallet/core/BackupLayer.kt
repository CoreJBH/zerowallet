package com.jbitmstr.purewallet.core

import com.jbitmstr.purewallet.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The backup layer: the encrypted channel backup, out and back in.
 *
 * Two rules shape it. The node is stopped while its store is read, because a running node
 * keeps rewriting channel state and a half-written monitor is worse than no backup; and the
 * store never sits in memory. The export writes the zip straight from the node directory to
 * a private file, seals that file into another, and the plaintext copy is overwritten and
 * deleted before this layer returns. A restore decrypts into a staging file, reads the
 * manifest from it, and only then unpacks it into a directory it swaps in.
 *
 * The file that leaves the phone is sealed with the wallet's own phrase (format 3,
 * PBKDF2-HMAC-SHA256 with a per-file salt), so Google Drive or any other destination holds
 * something that is random bytes without the twelve words.
 */
internal class BackupLayer(
    private val core: WalletCore,
    private val runtime: NodeRuntime
) {

    /**
     * Packs this device's node store into an encrypted `.zwbackup` ready for the share sheet
     * or the file picker.
     *
     * The node is stopped only while its files are copied, and is started again before this
     * returns, so the picker does not leave the wallet offline.
     */
    suspend fun exportChannelBackup(): Result<ChannelBackupExport> = withContext(Dispatchers.IO) {
        runCatching {
            core.nodeMutex.withLock {
                val mnemonic = core.seedOrNull()
                    ?: throw IllegalStateException("This device has no recovery phrase to seal a backup with")
                val network = core.prefs.network
                val wasRunning = core.node != null
                // Read before the node is stopped: the manifest has to name the wallet and its
                // channels even when this session never had the node up.
                val (manifestNodeId, manifestChannels) = backupIdentity(network)
                val exportDir = File(core.appContext.cacheDir, BACKUP_DIR).apply { mkdirs() }
                // Anything an earlier export or share left behind goes first: the cache holds
                // one sealed copy of this wallet's channel data at a time, and it belongs to
                // the file about to be written.
                exportDir.listFiles()?.forEach { runCatching { it.delete() } }
                core.update { it.copy(busy = "Packing channel backup") }
                var plain: File? = null
                try {
                    runtime.stopNodeLocked(keepState = true)
                    val dir = core.networkDir(network)
                    val manifest = BackupManifest(
                        network = network.id,
                        createdAtEpochSecs = System.currentTimeMillis() / 1000L,
                        nodeId = manifestNodeId,
                        channelCount = manifestChannels,
                        appVersion = BuildConfig.VERSION_NAME,
                        wordCount = Bip39.wordCount(mnemonic)
                    )
                    plain = File.createTempFile("zwbackup-", ".zip", core.appContext.cacheDir)
                    val archiveFile = plain!!
                    val fileCount = archiveFile.outputStream().use { out ->
                        ChannelBackup.writeArchive(manifest, dir, out)
                    }
                    val sealed = File(
                        exportDir,
                        ChannelBackup.suggestedFileName(network.id, manifest.createdAtEpochSecs)
                    )
                    archiveFile.inputStream().use { input ->
                        sealed.outputStream().use { out ->
                            ChannelBackup.encryptStream(mnemonic, input, out)
                        }
                    }
                    // Remembered so Settings can say how old the last backup is: a snapshot
                    // nobody refreshed is the one that costs a force-close.
                    core.prefs.recordChannelBackup(manifest.createdAtEpochSecs, manifest.channelCount)
                    core.update {
                        it.copy(
                            lastChannelBackupEpochSecs = manifest.createdAtEpochSecs,
                            lastChannelBackupChannels = manifest.channelCount
                        )
                    }
                    ChannelBackupExport(
                        file = sealed,
                        fileName = sealed.name,
                        manifest = manifest,
                        fileCount = fileCount,
                        sizeBytes = sealed.length()
                    )
                } finally {
                    // The archive is the node's plaintext store on disk: overwrite it before
                    // it goes, so a later read of a freed block finds nothing.
                    plain?.let { wipeAndDelete(it) }
                    core.update { it.copy(busy = null) }
                    if (wasRunning) runtime.startNodeLocked(mnemonic)
                }
            }
        }
    }

    /**
     * Opens a backup file far enough to say what is in it, without replacing anything.
     *
     * The restore confirmation needs this before the user commits: the date the file was
     * made, the wallet it belongs to, and whether restoring it here is allowed at all. The
     * file is decrypted to a scratch copy (the tag is only checked at the end, so a wrong
     * phrase must fail here and not on the first read of an entry), the manifest is read from
     * that copy, and the copy is wiped.
     */
    suspend fun inspectChannelBackup(file: File): Result<BackupReview> = withContext(Dispatchers.IO) {
        runCatching {
            val mnemonic = core.seedOrNull()
                ?: throw IllegalStateException("Open the wallet first, then open the backup")
            val archive = decryptToScratch(mnemonic, file)
            try {
                val manifest = archive.inputStream().use { ChannelBackup.readManifest(it) }
                ChannelBackupReview.review(
                    manifest = manifest,
                    networkId = core.prefs.network.id,
                    // The live id, or the one this device last ran with: a backup opened while
                    // the node is stopped still has a wallet to be compared against.
                    nodeId = core.snapshot.nodeId.ifBlank { core.prefs.lastNodeId(core.prefs.network) },
                    nowEpochSecs = System.currentTimeMillis() / 1000L,
                    formatVersion = file.inputStream().use { ChannelBackup.formatOf(it) }
                )
            } finally {
                wipeAndDelete(archive)
            }
        }
    }

    /**
     * Replaces this device's node store with the one inside [file].
     *
     * The file is opened with the recovery phrase already on this device, so a backup made
     * from a different phrase fails here before anything is touched. The old store is moved
     * aside rather than deleted.
     *
     * Restoring the data and starting a node on it are two steps, and only the first decides
     * whether the old store comes back. A backup that could not be read, or could not be
     * written in place, is rolled back before anything is committed. Once the restored data
     * is in place it stays: a start that failed because the Esplora endpoint timed out, the
     * phone is offline or the Lightning port is taken says nothing about the backup, and
     * throwing a good restore away for it used to leave the user with neither the backup nor
     * the store it replaced. The node is simply started again when the network is back.
     */
    suspend fun importChannelBackup(file: File): Result<BackupManifest> = withContext(Dispatchers.IO) {
        runCatching {
            core.nodeMutex.withLock {
                val mnemonic = core.seedOrNull()
                    ?: throw IllegalStateException("Restore your recovery phrase first, then import the backup")
                core.update { it.copy(busy = "Opening channel backup") }
                val archive = decryptToScratch(mnemonic, file)
                try {
                    val manifest = archive.inputStream().use { ChannelBackup.readManifest(it) }
                    val network = ChainNetwork.fromId(manifest.network)
                    // Decided here as well as on the screen: a backup of another chain or of
                    // another wallet is refused even if the sheet that opened it was wrong,
                    // and the sheet's own warning is what the user actually reads.
                    val review = ChannelBackupReview.review(
                        manifest = manifest,
                        networkId = core.prefs.network.id,
                        nodeId = core.snapshot.nodeId.ifBlank { core.prefs.lastNodeId(core.prefs.network) },
                        nowEpochSecs = System.currentTimeMillis() / 1000L,
                        formatVersion = file.inputStream().use { ChannelBackup.formatOf(it) }
                    )
                    if (!review.canRestore) throw IllegalStateException(review.problems.first())

                    runtime.stopNodeLocked(keepState = false)
                    val dir = core.networkDir(network)
                    val stamp = System.currentTimeMillis()
                    // The chain is in the name, so a start on any chain can tell which store
                    // the folders belong to (see [NodeDirs]).
                    val incoming = File(core.ldkRoot(), NodeDirs.incoming(network.id, stamp)).apply { mkdirs() }
                    val previous = File(core.ldkRoot(), NodeDirs.previous(network.id, stamp))
                    try {
                        archive.inputStream().use { input -> ChannelBackup.extractTo(input, incoming) }
                        if (dir.exists() && !dir.renameTo(previous)) {
                            throw IllegalStateException("The current node data could not be moved aside")
                        }
                        if (!incoming.renameTo(dir)) {
                            throw IllegalStateException("The backup could not be put in place")
                        }
                    } catch (t: Throwable) {
                        // Nothing was committed: the backup itself could not be written over
                        // the store that was here, so put that store back.
                        incoming.deleteRecursively()
                        if (!dir.exists() && previous.exists()) previous.renameTo(dir)
                        throw t
                    }

                    // From here the restored data is the wallet's store. Only a failure that
                    // points at that data itself puts the previous store back.
                    val startFailure = runtime.startNodeLocked(mnemonic)
                    if (startFailure != null) {
                        if (EsploraFallback.isChainSourceFailure(startFailure)) {
                            previous.deleteRecursively()
                            throw IllegalStateException(
                                "The channel backup was restored, but the node could not start yet: " +
                                    "${core.describe(startFailure)} Your restored data is in place - start the " +
                                    "node again when the connection is back."
                            )
                        }
                        // The node refused the store the backup carried: keep the one that was
                        // here rather than leaving a wallet that cannot open its own data.
                        runtime.stopNodeLocked(keepState = false)
                        dir.deleteRecursively()
                        if (previous.exists()) previous.renameTo(dir)
                        runtime.startNodeLocked(mnemonic)
                        throw IllegalStateException(
                            "The node could not start from this backup. The data that was on this phone was kept."
                        )
                    }
                    previous.deleteRecursively()
                    // The store now holds the state the file carried, so the age Settings shows
                    // is the backup's, not the moment it was restored.
                    core.prefs.recordChannelBackup(manifest.createdAtEpochSecs, manifest.channelCount)
                    core.update {
                        it.copy(
                            lastChannelBackupEpochSecs = manifest.createdAtEpochSecs,
                            lastChannelBackupChannels = manifest.channelCount
                        )
                    }
                    core.say("Channel backup restored")
                    manifest
                } finally {
                    wipeAndDelete(archive)
                    core.update { it.copy(busy = null) }
                }
            }
        }
    }

    /**
     * The wallet identity a channel backup manifest should carry.
     *
     * A backup can be exported while the node is stopped - the store is on disk and is still
     * the thing worth saving - and the live state then has no node id at all. The last identity
     * this device recorded stands in for it, so the file names the wallet it came from instead
     * of leaving the one field that stops it being restored elsewhere empty.
     */
    private fun backupIdentity(network: ChainNetwork): Pair<String, Int> {
        val live = core.snapshot
        return if (live.nodeId.isNotBlank()) {
            live.nodeId to live.channels.size
        } else {
            core.prefs.lastNodeId(network) to core.prefs.lastKnownChannelCount(network)
        }
    }

    /** Decrypts [sealedFile] into a scratch zip under the app's cache, streaming all the way. */
    private fun decryptToScratch(mnemonic: String, sealedFile: File): File {
        val scratch = File.createTempFile("zwrestore-", ".zip", core.appContext.cacheDir)
        try {
            sealedFile.inputStream().use { input ->
                scratch.outputStream().use { out ->
                    ChannelBackup.decryptStream(mnemonic, input, out)
                }
            }
            return scratch
        } catch (t: Throwable) {
            wipeAndDelete(scratch)
            throw t
        }
    }

    companion object {
        /** Where sealed exports wait for the share sheet or the file picker. */
        const val BACKUP_DIR = "backups"

        /**
         * Overwrites [file] with zeros and deletes it.
         *
         * Best effort, and said so: flash storage may keep a copy in a block the filesystem
         * does not write again, so this is not a guarantee - it just means the next reader of
         * that space does not find a node store lying in a temporary file. The data that
         * matters is sealed with the phrase; this is the plaintext between the two.
         */
        fun wipeAndDelete(file: File) {
            runCatching {
                if (file.isFile) {
                    val length = file.length()
                    file.outputStream().use { out ->
                        val zeros = ByteArray(64 * 1024)
                        var written = 0L
                        while (written < length) {
                            val piece = minOf(zeros.size.toLong(), length - written).toInt()
                            out.write(zeros, 0, piece)
                            written += piece
                        }
                        out.flush()
                    }
                }
            }
            runCatching { file.deleteRecursively() }
        }
    }
}
