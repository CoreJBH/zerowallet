package com.corejbh.zerowallet.core

import android.app.Activity
import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings sheet shows about the last backup transfer. */
data class BackupTransferState(
    val message: String? = null,
    val failed: Boolean = false,
    /** A backup the user just picked, decrypted no further than its header, waiting for confirmation. */
    val restoreFile: File? = null
)

/**
 * Owns the two Android file-picker round trips for the encrypted channel backup.
 *
 * The pickers are registered on the activity, not on a composable, because Android's own
 * file picker (where Google Drive appears) puts the app in the background: with lock-on-open
 * the wallet then leaves composition, and a launcher that lived in the sheet would be gone
 * before its result arrived. Everything here is application scoped for the same reason, and
 * no Google Play Services, OAuth client or storage permission is involved: the picker hands
 * back a `content://` URI and the transfer reads or writes exactly that document.
 *
 * **Files, not byte arrays.** A picked backup used to be read whole into memory (up to 64
 * MiB) before anything looked at it. It is now copied to a private file under
 * `cache/backups` in 64 KiB pieces, with the size cap enforced while copying, and the restore
 * works from that file. A saved export is streamed into the chosen document the same way, and
 * the copy in the cache is deleted as soon as the write finishes or the dialog is cancelled.
 */
object BackupTransfer {

    /**
     * Largest file this wallet reads from the picker. A channel backup for a phone-sized
     * wallet is a few megabytes at most; the cap keeps a wrong pick (a video, a huge ZIP)
     * from being copied at all, and matches the limit the backup format itself opens.
     */
    const val MAX_BACKUP_BYTES = ChannelBackup.MAX_BACKUP_BYTES

    /**
     * How long a prepared export waits for its picker result. Long enough for a person to
     * choose a folder in Google Drive, short enough that an export whose result never came
     * back does not sit on disk for the life of the process.
     */
    private const val PENDING_EXPORT_TTL_MS = 10L * 60L * 1000L

    /**
     * How long a sealed backup stays in the cache after it was handed to the share sheet.
     *
     * The receiving app reads the file from its `content://` URI after this wallet has
     * returned to the screen - Drive uploads it, Gmail attaches it - so it must not be deleted
     * when the chooser closes. Ten minutes covers that upload; after it, the file is a sealed
     * duplicate of the node store that the wallet can produce again in one tap, and it is
     * deleted at the next resume, the next export or a wipe, whichever comes first.
     */
    private const val SHARED_EXPORT_TTL_MS = 10L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var host: Activity? = null
    private var saveLauncher: ((String) -> Unit)? = null
    private var openLauncher: (() -> Unit)? = null

    /**
     * The sealed export a pending "save as" dialog will write, and when it was prepared.
     *
     * It outlives the activity on purpose: a rotation destroys and recreates the activity while
     * the picker is open, and androidx hands the picker's result to the new instance. Dropping
     * this in [unbind] left the save with nothing to write, so the file was never created and
     * the user saw a cancelled dialog for a backup that had already been prepared. A sealed
     * archive is not secret to the app, and it is dropped on a result, a cancel, or after
     * [PENDING_EXPORT_TTL_MS].
     */
    private var pendingExport: ChannelBackupExport? = null
    private var pendingExportAt: Long = 0L

    private val _state = MutableStateFlow(BackupTransferState())
    val state: StateFlow<BackupTransferState> = _state.asStateFlow()

    /** Called by the activity once its picker launchers exist. */
    fun bind(activity: Activity, save: (String) -> Unit, open: () -> Unit) {
        host = activity
        saveLauncher = save
        openLauncher = open
    }

    /**
     * Called when that activity goes away, so nothing holds it.
     *
     * @param activityRecreating the activity is being destroyed to be built again (a
     * rotation, a display change). The prepared export is kept then, because the picker's
     * result comes back to the new activity and needs it.
     */
    fun unbind(activity: Activity, activityRecreating: Boolean = false) {
        if (host !== activity) return
        host = null
        saveLauncher = null
        openLauncher = null
        if (!activityRecreating) {
            pendingExport = null
            pendingExportAt = 0L
        }
    }

    /** Opens "save as" for [export]; returns false when the picker is not available. */
    fun requestSave(export: ChannelBackupExport): Boolean {
        val launch = saveLauncher ?: return false
        pendingExport = export
        pendingExportAt = System.currentTimeMillis()
        _state.value = BackupTransferState()
        launch(export.fileName)
        return true
    }

    /** Opens "open file" to pick a `.zwbackup`; returns false when the picker is not available. */
    fun requestOpen(): Boolean {
        val launch = openLauncher ?: return false
        _state.value = BackupTransferState()
        launch()
        return true
    }

    /** The user finished the "save as" dialog. */
    fun onSaveResult(context: Context, uri: Uri?) {
        val export = pendingExport
        val prepared = System.currentTimeMillis() - pendingExportAt
        pendingExport = null
        pendingExportAt = 0L
        if (export == null || prepared > PENDING_EXPORT_TTL_MS) {
            // Nothing was written, and only a real pick (not a cancel) is worth a message.
            if (uri != null) {
                publish("The prepared backup expired before it was saved. Run the backup again.", failed = true)
            }
            export?.let { BackupLayer.wipeAndDelete(it.file) }
            return
        }
        if (uri == null) {
            // Cancelled: the sealed copy in the cache has no other purpose now.
            BackupLayer.wipeAndDelete(export.file)
            publish("Backup cancelled", failed = false)
            return
        }
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val written = runCatching {
                val target = resolver.openOutputStream(uri)
                    ?: error("the chosen file could not be opened")
                target.use { out ->
                    export.file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                }
            }
            // Either way the cache's copy is done: it was just written into the document the
            // user chose, or the write failed and the file is stale.
            BackupLayer.wipeAndDelete(export.file)
            written.onSuccess {
                publish(
                    "Saved ${export.fileName} - ${export.manifest.channelCount} channels, " +
                        "${(export.sizeBytes / 1024).coerceAtLeast(1)} KB, sealed with your recovery phrase.",
                    failed = false
                )
            }.onFailure {
                publish("Could not save the backup: ${it.message}", failed = true)
            }
        }
    }

    /**
     * The user picked a file to restore from.
     *
     * The document is copied into the app's own cache while it is read, so the size cap is
     * enforced on the bytes that actually arrive rather than on a length a `content://`
     * stream may not report at all.
     */
    fun onOpenResult(context: Context, uri: Uri?) {
        if (uri == null) return
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val picked = runCatching {
                val directory = File(context.applicationContext.cacheDir, BackupLayer.BACKUP_DIR)
                    .apply { mkdirs() }
                val target = File(directory, "picked-${System.currentTimeMillis()}.zwbackup")
                try {
                    val input = resolver.openInputStream(uri)
                        ?: error("the picked file could not be opened")
                    input.use { source -> target.outputStream().use { sink -> copyLimited(source, sink, MAX_BACKUP_BYTES.toLong()) } }
                    target
                } catch (t: Throwable) {
                    BackupLayer.wipeAndDelete(target)
                    throw t
                }
            }.getOrNull()
            if (picked == null) {
                publish("That file could not be read, or it is too large to be a channel backup", failed = true)
            } else {
                _state.update { it.copy(restoreFile = picked, message = null, failed = false) }
            }
        }
    }

    /** Copies at most [limit] bytes, failing instead of truncating when the source is longer. */
    private fun copyLimited(source: java.io.InputStream, sink: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) error("the picked file is larger than $limit bytes")
            sink.write(buffer, 0, read)
        }
        buffer.fill(0)
        sink.flush()
    }

    /** The UI has shown the picked backup and asks for confirmation; forget the file. */
    fun consumeRestoreFile() {
        _state.update { it.copy(restoreFile = null) }
    }

    /** Deletes a picked file once its restore was confirmed, refused or abandoned. */
    fun discard(file: File?) {
        file?.let { BackupLayer.wipeAndDelete(it) }
    }

    /**
     * Deletes sealed exports that have waited in the cache longer than [SHARED_EXPORT_TTL_MS].
     *
     * Called when the app comes back to the screen and before a new export, so the cache never
     * holds a channel backup from an earlier session - which is what "the temporary file is
     * cleaned up after sharing" means when the share itself has no end this app can observe:
     * the chooser closes at once, but the app it chose may still be reading.
     */
    fun purgeStaleExports(context: Context, now: Long = System.currentTimeMillis()) {
        scope.launch {
            val directory = File(context.applicationContext.cacheDir, BackupLayer.BACKUP_DIR)
            directory.listFiles()?.forEach { file ->
                if (now - file.lastModified() > SHARED_EXPORT_TTL_MS) {
                    BackupLayer.wipeAndDelete(file)
                }
            }
        }
    }

    fun report(message: String, failed: Boolean) = publish(message, failed)

    /** Forgets the last message, e.g. when the sheet starts a different backup action. */
    fun reset() {
        _state.value = BackupTransferState()
    }

    private fun publish(message: String, failed: Boolean) {
        _state.update { it.copy(message = message, failed = failed) }
    }
}
