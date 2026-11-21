package com.jbitmstr.purewallet.ui.sheets

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.BackupTransfer
import com.jbitmstr.purewallet.core.ChannelBackup
import com.jbitmstr.purewallet.core.ChannelBackupExport
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.WalletState
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.LabelRow
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SectionLabel
import com.jbitmstr.purewallet.ui.components.SheetHeader
import com.jbitmstr.purewallet.ui.components.WalletSheet
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal
import java.io.File
import kotlinx.coroutines.launch

/** Settings: network, Esplora endpoint, gossip source, node identity, seed and wipe. */
@Composable
fun SettingsSheet(
    vm: WalletViewModel,
    state: WalletState,
    lockAvailable: Boolean,
    onBackup: () -> Unit,
    onAuthorize: (String, () -> Unit) -> Unit,
    onClose: () -> Unit
) {
    var esplora by remember(state.esploraUrl) { mutableStateOf(state.esploraUrl) }
    var esploraError by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<ByteArray?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfer by BackupTransfer.state.collectAsStateWithLifecycle()

    // The picked file arrives through the activity-owned picker, so it still lands even when
    // the picker backgrounded the app and lock-on-open took the wallet out of composition.
    LaunchedEffect(transfer.restoreBytes) {
        val bytes = transfer.restoreBytes ?: return@LaunchedEffect
        pendingRestore = bytes
        BackupTransfer.consumeRestoreBytes()
    }

    val statusText = backupError ?: transfer.message ?: backupNote
    val statusFailed = backupError != null || (transfer.message != null && transfer.failed)

    fun prepareBackup(share: Boolean) {
        onAuthorize("Authenticate to save an encrypted channel backup.") {
            scope.launch {
                backupBusy = true
                backupError = null
                backupNote = null
                vm.exportChannelBackup()
                    .onSuccess { export ->
                        if (share) {
                            runCatching { shareBackup(context, export) }
                                .onFailure { backupError = "Could not share the backup: ${it.message}" }
                            backupNote = "Backup ready: ${export.manifest.channelCount} channels, sealed " +
                                "with your recovery phrase."
                        } else if (!BackupTransfer.requestSave(export)) {
                            backupError = "The file picker is not available right now"
                        }
                    }
                    .onFailure { backupError = it.message ?: "The backup could not be prepared" }
                backupBusy = false
            }
        }
    }

    WalletSheet(onDismiss = onClose) {
        SheetHeader(
            title = "Settings",
            subtitle = "Everything runs on this device. Nothing is registered anywhere.",
            onClose = onClose
        )

        if (!state.backedUp) {
            Banner(
                title = "Recovery phrase not backed up",
                body = "Without your recovery phrase, a lost phone means lost funds.",
                actionLabel = "Show phrase",
                onAction = onBackup
            )
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("Node")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface)
                .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            LabelRow("Status", if (state.running) "Running" else if (state.starting) "Starting" else "Stopped")
            Spacer(Modifier.height(8.dp))
            LabelRow("Block height", state.bestHeight?.toString() ?: "—")
            Spacer(Modifier.height(8.dp))
            LabelRow(
                "Last sync",
                state.lastSyncEpochSecs?.let { Fmt.dateTime(it) } ?: "—"
            )
            Spacer(Modifier.height(8.dp))
            LabelRow("Network", state.network.title)

            Spacer(Modifier.height(12.dp))
            Text("Node id", color = Pal.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                state.nodeId.ifEmpty { "—" },
                color = Pal.TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            if (state.listeningAddresses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Listening on", color = Pal.TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.listeningAddresses.joinToString(", "),
                    color = Pal.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Copy id",
                    onClick = {
                        clipboard.setText(AnnotatedString(state.nodeId))
                        Toast.makeText(context, "Node id copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state.nodeId.isNotEmpty()
                )
                GhostButton(
                    text = "Sync now",
                    onClick = { vm.refresh() },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Restart node",
                    onClick = { vm.restartNode() },
                    modifier = Modifier.weight(1f)
                )
                GhostButton(
                    text = "Stop node",
                    onClick = { vm.stopNode() },
                    modifier = Modifier.weight(1f),
                    enabled = state.running
                )
            }
        }

        SectionLabel("Network")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface)
                .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
        ) {
            ChainNetwork.values().forEachIndexed { index, network ->
                NetworkRow(
                    network = network,
                    selected = state.network == network,
                    onClick = { vm.switchNetwork(network) }
                )
                if (index != ChainNetwork.values().lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Border))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Each network uses its own wallet store, and switching restarts the node. Test coins have no value.",
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        SectionLabel("Esplora endpoint")

        WalletTextField(
            value = esplora,
            onValueChange = { esplora = it },
            placeholder = state.network.defaultEsploraUrl,
            label = "HTTP API base URL",
            singleLine = false,
            minLines = 2
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Save endpoint",
                onClick = {
                    esploraError = null
                    vm.setEsploraUrl(esplora).onFailure { esploraError = it.message }
                },
                modifier = Modifier.weight(1f)
            )
            GhostButton(
                text = "Use default",
                onClick = {
                    esploraError = null
                    esplora = state.network.defaultEsploraUrl
                    vm.setEsploraUrl(state.network.defaultEsploraUrl)
                },
                modifier = Modifier.weight(1f)
            )
        }
        if (esploraError != null) {
            Spacer(Modifier.height(8.dp))
            Text(esploraError ?: "", color = Pal.Danger, fontSize = 12.sp, lineHeight = 16.sp)
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Only https:// endpoints are accepted. Plain http:// is allowed just for a node " +
                    "on this phone (127.0.0.1 or localhost).",
                color = Pal.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        SectionLabel("Gossip")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface)
                .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Rapid Gossip Sync", color = Pal.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Off: learn the network from peers over the Lightning P2P network. On: fetch a signed snapshot instead.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.rgsEnabled,
                onCheckedChange = { vm.setRapidGossipSync(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Pal.OnAccent,
                    checkedTrackColor = Pal.Accent,
                    uncheckedThumbColor = Pal.TextSecondary,
                    uncheckedTrackColor = Pal.SurfaceElevated,
                    uncheckedBorderColor = Pal.Border
                )
            )
        }

        SectionLabel("Security")

        SwitchRow(
            title = "Lock on open",
            detail = if (lockAvailable) {
                "Off by default. When on, the app asks for your fingerprint or screen lock " +
                    "every time it opens or comes back from the background."
            } else {
                "Set a screen lock (PIN, pattern or password) on this phone to lock the wallet."
            },
            checked = state.lockOnOpen,
            enabled = lockAvailable,
            onCheckedChange = { vm.setLockOnOpen(it) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (lockAvailable) {
                "Sending funds, funding a channel and showing the recovery phrase always ask " +
                    "for your fingerprint or screen lock before they go ahead."
            } else {
                "Without a screen lock on this phone the wallet cannot ask for authentication, " +
                    "so those confirmations are skipped."
            },
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        SwitchRow(
            title = "Block screenshots",
            detail = "Keep the wallet out of screenshots, screen recordings and the recent-apps preview.",
            checked = state.screenProtection,
            enabled = true,
            onCheckedChange = { vm.setScreenProtection(it) }
        )

        SectionLabel("Recovery")

        GhostButton(
            text = "Show recovery phrase",
            onClick = onBackup,
            modifier = Modifier.fillMaxWidth()
        )

        SectionLabel("Lightning channel backup")

        Text(
            "Open channels live only on this phone. This saves the node's channel data as one " +
                "file, locked with your recovery phrase, so Google Drive or the share sheet can hold " +
                "it without anyone being able to read it.",
            color = Pal.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = if (backupBusy) "Working" else "Save backup to Drive",
            onClick = { prepareBackup(share = false) },
            enabled = !backupBusy && state.seedPresent,
            loading = backupBusy
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Share backup",
                onClick = { prepareBackup(share = true) },
                modifier = Modifier.weight(1f),
                enabled = !backupBusy && state.seedPresent
            )
            GhostButton(
                text = "Restore backup",
                onClick = {
                    backupError = null
                    if (!BackupTransfer.requestOpen()) {
                        backupError = "The file picker is not available right now"
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !backupBusy && state.seedPresent
            )
        }
        if (statusText != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                color = if (statusFailed) Pal.Danger else Pal.Positive,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Only these same 12 or 24 words open it - ZeroWallet cannot open it for you. Restore " +
                "works after the phrase is on the phone: pick the file and the node replaces its " +
                "channel data with it. A backup is a snapshot, so a channel that changed after it " +
                "was saved may close when you restore.",
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(24.dp))

        GhostButton(
            text = "Wipe wallet from this device",
            onClick = { confirmWipe = true },
            modifier = Modifier.fillMaxWidth(),
            danger = true
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Deletes the encrypted seed, node database and settings from this phone. Anything still on-chain stays on-chain.",
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }

    pendingRestore?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = Pal.SurfaceElevated,
            titleContentColor = Pal.TextPrimary,
            textContentColor = Pal.TextSecondary,
            title = { Text("Restore channel backup?") },
            text = {
                Text(
                    "The channel data on this phone is replaced with the backup and the node " +
                        "restarts. This is how a replacement phone gets its open channels back.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        backupBusy = true
                        backupError = null
                        backupNote = null
                        BackupTransfer.reset()
                        vm.importChannelBackup(bytes)
                            .onSuccess { manifest: BackupManifest ->
                                backupNote = "Restored ${manifest.channelCount} channels from a " +
                                    "${manifest.network} backup"
                            }
                            .onFailure { backupError = it.message ?: "The backup could not be restored" }
                        backupBusy = false
                    }
                }) {
                    Text("Restore", color = Pal.AccentBright)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text("Cancel", color = Pal.TextSecondary)
                }
            }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = Pal.SurfaceElevated,
            titleContentColor = Pal.TextPrimary,
            textContentColor = Pal.TextSecondary,
            title = { Text("Wipe this wallet?") },
            text = {
                Text(
                    "You will need your recovery phrase to restore it. This cannot be undone.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    vm.wipeWallet()
                    onClose()
                }) {
                    Text("Wipe", color = Pal.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("Cancel", color = Pal.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface)
            .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Pal.TextPrimary else Pal.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(detail, color = Pal.TextTertiary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Pal.OnAccent,
                checkedTrackColor = Pal.Accent,
                uncheckedThumbColor = Pal.TextSecondary,
                uncheckedTrackColor = Pal.SurfaceElevated,
                uncheckedBorderColor = Pal.Border
            )
        )
    }
}

@Composable
private fun NetworkRow(network: ChainNetwork, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (selected) Pal.Accent else Color.Transparent)
                .border(1.dp, if (selected) Pal.AccentBright else Pal.Border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Pal.OnAccent))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(network.title, color = Pal.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(network.detail, color = Pal.TextTertiary, fontSize = 11.sp)
        }
    }
}

/**
 * Hands the sealed backup to the Android share sheet, where Google Drive, Gmail and any other
 * app can take it. The bytes go to the app's own cache and leave it through a FileProvider
 * URI, so no storage permission is needed and nothing is world-readable.
 */
private fun shareBackup(context: Context, export: ChannelBackupExport) {
    val file = File(File(context.cacheDir, "backups"), export.fileName)
    file.parentFile?.mkdirs()
    file.writeBytes(export.bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = ChannelBackup.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ZeroWallet encrypted channel backup")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share channel backup"))
}
