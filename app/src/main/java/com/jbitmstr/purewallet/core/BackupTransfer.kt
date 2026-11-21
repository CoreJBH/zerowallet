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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var host: Activity? = null
    private var saveLauncher: ((String) -> Unit)? = null
    private var openLauncher: (() -> Unit)? = null
    private var pendingExport: ChannelBackupExport? = null

    private val _state = MutableStateFlow(BackupTransferState())
    val state: StateFlow<BackupTransferState> = _state.asStateFlow()

    /** Called by the activity once its picker launchers exist. */
    fun bind(activity: Activity, save: (String) -> Unit, open: () -> Unit) {
        host = activity
        saveLauncher = save
        openLauncher = open
    }

    /** Called when that activity goes away, so nothing holds it. */
    fun unbind(activity: Activity) {
        if (host === activity) {
            host = null
            saveLauncher = null
            openLauncher = null
            pendingExport = null
        }
    }

    /** Opens "save as" for [export]; returns false when the picker is not available. */
    fun requestSave(export: ChannelBackupExport): Boolean {
        val launch = saveLauncher ?: return false
        pendingExport = export
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
        val export = pendingExport ?: return
        pendingExport = null
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
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) {
                publish("That file could not be read", failed = true)
            } else {
                _state.update { it.copy(restoreBytes = bytes, message = null, failed = false) }
            }
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
