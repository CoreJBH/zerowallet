package com.jbitmstr.purewallet.ui.sheets

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.Authorization
import com.jbitmstr.purewallet.core.BackupManifest
import com.jbitmstr.purewallet.core.BackupReview
import com.jbitmstr.purewallet.core.BackupTransfer
import com.jbitmstr.purewallet.core.ChannelBackup
import com.jbitmstr.purewallet.core.ChannelBackupExport
import com.jbitmstr.purewallet.core.ChannelBackupReview
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.DeviceLockSettings
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.SensitiveClipboard
import com.jbitmstr.purewallet.core.SecureStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: network, Esplora endpoint, gossip source, node identity, seed and wipe. */
@Composable
fun SettingsSheet(
    vm: WalletViewModel,
    state: WalletState,
    lockAvailable: Boolean,
    onBackup: () -> Unit,
    authorization: Authorization,
    onClose: () -> Unit
) {
    var esplora by remember(state.esploraUrl) { mutableStateOf(state.esploraUrl) }
    var esploraError by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var wiping by remember { mutableStateOf(false) }
    var wipeError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfer by BackupTransfer.state.collectAsStateWithLifecycle()
    // The age of the last backup is shown against the moment the sheet opened; a snapshot is
    // judged in days, so a second one way or the other is not what this screen is for.
    val nowEpochSecs = remember { System.currentTimeMillis() / 1000L }

    // The picked file arrives through the activity-owned picker, so it still lands even when
    // the picker backgrounded the app and lock-on-open took the wallet out of composition.
    LaunchedEffect(transfer.restoreFile) {
        val file = transfer.restoreFile ?: return@LaunchedEffect
        BackupTransfer.consumeRestoreFile()
        // Read the file before anything is asked or replaced. The date it was made, the
        // wallet it belongs to and whether it may be restored here are what the confirmation
        // is built from, and a file that cannot be opened says so without touching the store.
        pendingRestore = PendingRestore(file = file, loading = true)
        pendingRestore = vm.inspectChannelBackup(file).fold(
            onSuccess = { review -> PendingRestore(file = file, review = review, loading = false) },
            onFailure = { failure ->
                PendingRestore(
                    file = file,
                    error = failure.message ?: "This backup could not be opened",
                    loading = false
                )
            }
        )
    }

    val statusText = backupError ?: transfer.message ?: backupNote
    val statusFailed = backupError != null || (transfer.message != null && transfer.failed)

    fun prepareBackup(share: Boolean) {
        authorization.guarded("Authenticate to save an encrypted channel backup.") {
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

            // A start that failed used to leave nothing but "Stopped" and ₿0 on screen.
            // Whatever the node said goes right here, under the status it belongs to.
            val nodeError = state.error
            if (nodeError != null && !state.running) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Could not start",
                    color = Pal.Danger,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    nodeError,
                    color = Pal.Danger,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Copy id",
                    onClick = {
                        SensitiveClipboard.copy(context, state.nodeId, "Node id copied")
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
                    text = if (state.starting) "Starting…" else "Restart node",
                    onClick = { vm.restartNode() },
                    modifier = Modifier.weight(1f),
                    enabled = !state.starting
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
            val active = state.activeEsploraUrl
            if (state.running && active != null && active != state.esploraUrl) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "In use now: $active. The endpoint above did not answer, so the node is " +
                        "reading this one instead.",
                    color = Pal.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Only https:// endpoints are accepted. Plain http:// is allowed just for a node " +
                    "on this phone (127.0.0.1 or localhost). Saving a different endpoint restarts " +
                    "the node, so it is in use right away.",
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
                    if (!state.rgsSupported) {
                        // A snapshot names the chain it belongs to, and this one has no
                        // server of its own: pointing it at another chain's snapshot would
                        // mean routing from a graph of a different network.
                        state.network.title + " has no Rapid Gossip Sync server of its own, " +
                            "so this chain always learns the network from peers."
                    } else {
                        "Off: learn the network from peers over the Lightning P2P network. " +
                            "On: fetch a signed snapshot from the server for this chain instead."
                    },
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.rgsEnabled && state.rgsSupported,
                onCheckedChange = { vm.setRapidGossipSync(it) },
                enabled = state.rgsSupported,
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
                    "for your fingerprint or screen lock before they go ahead. The recovery " +
                    "phrase itself is sealed with a key in the Android Keystore, and where this " +
                    "phone allows it that key is bound to your screen lock, so the screen is not " +
                    "the only thing in the way: the key status below says which of the two this " +
                    "wallet has. That binding is also why opening the wallet after Android closed " +
                    "the app can ask for your screen lock even with lock-on-open off."
            } else {
                "This phone has no screen lock, so the wallet cannot confirm that it is you. " +
                    "Sending, showing the recovery phrase and wiping are blocked until you set " +
                    "one in Android settings. Receiving still works."
            },
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        if (!lockAvailable) {
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Open Android security settings",
                onClick = { DeviceLockSettings.open(context) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        // What the Keystore actually did with the key, read from the key itself. A wallet
        // sealed before this phone had a screen lock, or on a phone whose Keystore refused the
        // bound key, says so here instead of leaving the stronger state to be assumed.
        val protection = state.seedProtection
        if (protection != null && protection.level != SecureStore.SeedProtection.NONE) {
            val bound = protection.level == SecureStore.SeedProtection.DEVICE_LOCK
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    if (bound) {
                        "Recovery phrase key: bound to the screen lock"
                    } else {
                        "Recovery phrase key: not bound to the screen lock"
                    },
                    color = if (bound) Pal.Positive else Pal.Warning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (protection.detail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        protection.detail,
                        color = Pal.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Where the node's own data lives, and what actually protects it. The phrase above is
        // sealed with a Keystore key; this store is not sealed at all, so the user should know
        // that the sandbox is the boundary rather than assume a second lock exists.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface)
                .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(
                "Node data on this phone",
                color = Pal.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Channels, the on-chain wallet and the node's store live in this app's own " +
                    "storage (files/ldk-node/). That copy is not sealed with your recovery " +
                    "phrase the way the phrase is: what protects it is the app sandbox - no " +
                    "other app can read it, it is left out of cloud backups, and a release " +
                    "build is not debuggable. A rooted phone or a compromise of the whole " +
                    "device is outside what this app can stop, which is why the phrase on " +
                    "paper, not the phone, is what you keep.",
                color = Pal.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
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
        // How old the last snapshot from this phone is, before the buttons that make a new
        // one: a backup nobody refreshed is the one that costs a force-close.
        val lastBackupAge = ChannelBackupReview.ageLabel(state.lastChannelBackupEpochSecs, nowEpochSecs)
        val hasChannels = state.channels.isNotEmpty()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Pal.Surface)
                .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                when {
                    lastBackupAge != null ->
                        "Last backup from this phone: $lastBackupAge" +
                            if (state.lastChannelBackupChannels > 0) {
                                " (${state.lastChannelBackupChannels} channels)"
                            } else {
                                ""
                            }
                    hasChannels -> "No backup has been saved from this phone"
                    else -> "No channel backup saved from this phone yet"
                },
                color = if (lastBackupAge == null && hasChannels) Pal.Warning else Pal.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (hasChannels) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "A backup only knows the channel states it saw. Every payment since then " +
                        "makes the file older than the channel, and an old snapshot closes the " +
                        "channels that moved when it is restored. Save a new one after your " +
                        "channels change, and keep the newest.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
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
                "works after the phrase is on the phone: pick the file, read what it holds, and " +
                "confirm - the node then replaces its channel data with it.",
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

    pendingRestore?.let { pending ->
        val review = pending.review
        val blocked = review != null && !review.canRestore
        AlertDialog(
            onDismissRequest = {
                BackupTransfer.discard(pending.file)
                pendingRestore = null
            },
            containerColor = Pal.SurfaceElevated,
            titleContentColor = Pal.TextPrimary,
            textContentColor = Pal.TextSecondary,
            title = {
                Text(
                    when {
                        blocked -> "This backup cannot be restored here"
                        else -> "Restore channel backup?"
                    }
                )
            },
            text = {
                // The warnings are longer than one screen on a phone, and a confirmation that
                // cannot be read to the end is not a warning.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        pending.loading -> Text(
                            "Opening the backup with your recovery phrase…",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        pending.error != null -> Text(
                            pending.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Pal.Danger
                        )

                        review != null -> {
                            LabelRow("Saved", backupDateLabel(review, nowEpochSecs))
                            Spacer(Modifier.height(6.dp))
                            LabelRow("Network", review.manifest.network)
                            Spacer(Modifier.height(6.dp))
                            LabelRow("Channels", review.manifest.channelCount.toString())
                            Spacer(Modifier.height(6.dp))
                            LabelRow(
                                "Node",
                                review.manifest.nodeId.takeIf { it.isNotBlank() }
                                    ?.let { Fmt.shortId(it) } ?: "not named"
                            )
                            if (review.problems.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                review.problems.forEach { problem ->
                                    Text(
                                        problem,
                                        color = Pal.Danger,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            review.warnings.forEach { warning ->
                                Text(
                                    warning,
                                    color = Pal.TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                "The channel data on this phone is replaced with the backup and " +
                                    "the node restarts.",
                                color = Pal.TextTertiary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            if (review.canRestore) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = pending.acknowledged,
                                        onCheckedChange = { checked ->
                                            pendingRestore = pending.copy(acknowledged = checked)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Pal.Accent,
                                            uncheckedColor = Pal.TextSecondary,
                                            checkmarkColor = Pal.OnAccent
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "I understand that restoring an older state can " +
                                            "force-close channels that changed since.",
                                        color = Pal.TextPrimary,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = review != null && review.canRestore && pending.acknowledged,
                    onClick = {
                        val file = pending.file
                        pendingRestore = null
                        // Replacing the channel data can cost the funds in a live channel, so
                        // it asks for the same proof as a payment before it runs.
                        authorization.guarded("Authenticate to restore a channel backup.") {
                            scope.launch {
                                backupBusy = true
                                backupError = null
                                backupNote = null
                                BackupTransfer.reset()
                                vm.importChannelBackup(file)
                                    .onSuccess { manifest: BackupManifest ->
                                        backupNote = "Restored ${manifest.channelCount} channels from a " +
                                            "${manifest.network} backup"
                                    }
                                    .onFailure {
                                        backupError = it.message ?: "The backup could not be restored"
                                    }
                                backupBusy = false
                            }
                        }
                    }
                ) {
                    Text("Restore", color = if (review?.canRestore == true) Pal.AccentBright else Pal.TextTertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    BackupTransfer.discard(pending.file)
                    pendingRestore = null
                }) {
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
                Column {
                    Text(
                        "You will need your recovery phrase to restore it. This cannot be undone.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    val failure = wipeError
                    if (failure != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(failure, color = Pal.Danger, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (wiping) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Deleting the wallet and its node data…",
                            color = Pal.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !wiping,
                    onClick = {
                        // A wipe deletes the only copy of the seed on this phone, so it asks
                        // for the same proof as a payment before it runs. The sheet closes
                        // only after the wipe answers: a delete that failed (a node that
                        // would not stop, a Keystore that would not drop its key) used to
                        // look exactly like one that worked, because the sheet was closed
                        // before the work had even started.
                        authorization.guarded("Authenticate to wipe this wallet.") {
                            scope.launch {
                                wiping = true
                                wipeError = null
                                val result = vm.wipeWallet()
                                wiping = false
                                result.onSuccess {
                                    confirmWipe = false
                                    onClose()
                                }.onFailure { failure ->
                                    wipeError = failure.message ?: "The wallet could not be wiped."
                                }
                            }
                        }
                    }
                ) {
                    Text("Wipe", color = Pal.Danger)
                }
            },
            dismissButton = {
                TextButton(enabled = !wiping, onClick = { confirmWipe = false }) {
                    Text("Cancel", color = Pal.TextSecondary)
                }
            }
        )
    }
}

/** A file picked for a restore, and what reading it said about itself. */
private data class PendingRestore(
    val file: File,
    val review: BackupReview? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val acknowledged: Boolean = false
)

/** "Nov 20, 21:14 (2 days ago)": when the file was made, in the one line that fits a dialog. */
private fun backupDateLabel(review: BackupReview, nowEpochSecs: Long): String {
    val created = review.manifest.createdAtEpochSecs
    if (created <= 0L) return "date not recorded"
    val age = ChannelBackupReview.ageLabel(created, nowEpochSecs)
    return Fmt.dateTime(created) + if (age != null) " ($age)" else ""
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
 * app can take it. The file is already in the app's cache - the export wrote it there - and
 * leaves through a FileProvider URI, so no storage permission is needed and nothing is
 * world-readable.
 *
 * Nothing is copied here, and nothing is deleted here either. The receiving app reads the
 * file after this wallet comes back to the screen, so deleting it when the chooser closes
 * would hand Drive or Gmail an empty document; the copy is removed ten minutes later, on the
 * next resume or the next export ([BackupTransfer.purgeStaleExports]), and a wipe takes the
 * whole directory. Only the URI work stays on the caller's thread, which is where the
 * activity needs it.
 */
private fun shareBackup(context: Context, export: ChannelBackupExport) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", export.file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = ChannelBackup.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ZeroWallet encrypted channel backup")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share channel backup"))
}

