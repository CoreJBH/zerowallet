package com.jbitmstr.purewallet.core

import android.app.Activity
import android.content.Context
import android.net.Uri
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
    /** Bytes of a backup the user just picked, waiting for confirmation. */
    val restoreBytes: ByteArray? = null
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
 */
object BackupTransfer {

    /**
     * Largest file this wallet reads from the picker. A channel backup for a phone-sized
     * wallet is a few megabytes at most; the cap keeps a wrong pick (a video, a huge ZIP)
     * from being loaded into memory, and matches the limit the backup format itself opens.
     */
    const val MAX_BACKUP_BYTES = ChannelBackup.MAX_BACKUP_BYTES

    /**
     * How long a prepared export waits for its picker result. Long enough for a person to
     * choose a folder in Google Drive, short enough that an export whose result never came
     * back does not sit in memory for the life of the process.
     */
    private const val PENDING_EXPORT_TTL_MS = 10L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var host: Activity? = null
    private var saveLauncher: ((String) -> Unit)? = null
    private var openLauncher: (() -> Unit)? = null

    /**
     * The seal a pending "save as" dialog will write, and when it was prepared.
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
            return
        }
        if (uri == null) {
            publish("Backup cancelled", failed = false)
            return
        }
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { it.write(export.bytes) }
                    ?: error("the chosen file could not be opened")
            }
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

    /** The user picked a file to restore from. */
    fun onOpenResult(context: Context, uri: Uri?) {
        if (uri == null) return
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val bytes = runCatching { readLimited(resolver.openInputStream(uri), MAX_BACKUP_BYTES) }
                .getOrNull()
            if (bytes == null) {
                publish("That file could not be read, or it is too large to be a channel backup", failed = true)
            } else {
                _state.update { it.copy(restoreBytes = bytes, message = null, failed = false) }
            }
        }
    }

    /**
     * Reads at most [limit] bytes and fails beyond that, so a document of unknown size
     * cannot be pulled into memory whole. A `content://` stream often reports no length at
     * all, which is why the cap is enforced while reading.
     */
    private fun readLimited(stream: java.io.InputStream?, limit: Int): ByteArray {
        val input = stream ?: error("the picked file could not be opened")
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) error("the picked file is larger than $limit bytes")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** The UI has shown the picked backup and asks for confirmation; forget the bytes. */
    fun consumeRestoreBytes() {
        _state.update { it.copy(restoreBytes = null) }
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
