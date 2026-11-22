package com.jbitmstr.purewallet.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.ChannelRow
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.WalletState
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SectionLabel
import com.jbitmstr.purewallet.ui.components.SheetHeader
import com.jbitmstr.purewallet.ui.components.WalletSheet
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal
import kotlinx.coroutines.launch

/**
 * Channels: open a direct P2P channel to any Lightning peer using the on-chain balance,
 * or close an existing one. No LSP is involved.
 */
@Composable
fun ChannelsSheet(
    vm: WalletViewModel,
    state: WalletState,
    initialPeer: String,
    onAuthorize: (String, (Boolean) -> Unit) -> Unit,
    onScan: () -> Unit,
    onClose: () -> Unit
) {
    var peerUri by remember { mutableStateOf(initialPeer) }
    var amountText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<ChannelRow?>(null) }
    var forceClose by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun openChannel() {
        val amount = Fmt.parseSats(amountText)
        if (amount == null) {
            error = "Enter a channel size in sats"
            return
        }
        onAuthorize("Authenticate to fund this channel.") {
            scope.launch {
                busy = true
                error = null
                vm.openChannel(peerUri.trim(), amount, 0L).onFailure {
                    error = it.message ?: "Could not open the channel"
                }
                busy = false
            }
        }
    }

    fun connectOnly() {
        scope.launch {
            busy = true
            error = null
            vm.connectPeer(peerUri.trim()).onFailure {
                error = it.message ?: "Could not connect to that peer"
            }
            busy = false
        }
    }

    WalletSheet(onDismiss = onClose) {
        SheetHeader(
            title = "Channels",
            subtitle = "Direct connections to Lightning peers, funded from your on-chain balance.",
            onClose = onClose
        )

        Banner(
            title = "Channel state lives on this phone",
            body = "Your recovery phrase restores on-chain bitcoin only. Open Lightning channels " +
                "are stored on this device and are not backed up to any cloud or server, so a lost " +
                "or wiped phone can lose the funds that are in them. Keep channels small."
        )

        Spacer(Modifier.height(14.dp))

        if (state.channels.isEmpty()) {
            Text(
                "No channels yet. Open one below to send and receive over Lightning.",
                color = Pal.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
        } else {
            state.channels.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    onClose = { closing = channel; forceClose = false },
                    onForceClose = { closing = channel; forceClose = true }
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        SectionLabel("Open a channel")

        WalletTextField(
            value = peerUri,
            onValueChange = { peerUri = it; error = null },
            placeholder = "pubkey@host:port",
            label = "Peer",
            singleLine = false,
            minLines = 2
        )

        Spacer(Modifier.height(12.dp))

        WalletTextField(
            value = amountText,
            onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
            placeholder = "Channel size in sats",
            label = "Size",
            keyboardType = KeyboardType.Number
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Spendable on-chain balance: ${Fmt.sats(state.onchainSpendableSats)}. A channel needs roughly 1% on-chain reserve plus the funding fee.",
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "Open channel",
            onClick = { openChannel() },
            loading = busy,
            enabled = peerUri.isNotBlank() && !state.starting
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Connect only",
                onClick = { connectOnly() },
                modifier = Modifier.weight(1f),
                enabled = peerUri.isNotBlank() && !busy
            )
            GhostButton(
                text = "Scan peer",
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.peers.isNotEmpty()) {
            SectionLabel("Peers")
            state.peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (peer.connected) Pal.Positive else Pal.TextTertiary)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            Fmt.shortId(peer.nodeId, 10, 6),
                            color = Pal.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(peer.address, color = Pal.TextTertiary, fontSize = 11.sp)
                    }
                    Text(
                        if (peer.connected) "Connected" else "Saved",
                        color = if (peer.connected) Pal.Positive else Pal.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    closing?.let { channel ->
        AlertDialog(
            onDismissRequest = { closing = null },
            containerColor = Pal.SurfaceElevated,
            titleContentColor = Pal.TextPrimary,
            textContentColor = Pal.TextSecondary,
            title = { Text(if (forceClose) "Force close channel?" else "Close channel?") },
            text = {
                Text(
                    if (forceClose) {
                        "This broadcasts the latest commitment transaction immediately. Funds return on-chain after the time lock, and the peer may see the channel as hostile."
                    } else {
                        "A cooperative close asks the peer to settle on-chain. Both sides pay the closing fee."
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = channel
                    closing = null
                    onAuthorize(if (forceClose) "Authenticate to force close this channel." else "Authenticate to close this channel.") {
                        scope.launch {
                            vm.closeChannel(target, forceClose).onFailure {
                                error = it.message ?: "Could not close the channel"
                            }
                        }
                    }
                }) {
                    Text(if (forceClose) "Force close" else "Close", color = Pal.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { closing = null }) {
                    Text("Cancel", color = Pal.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun ChannelCard(channel: ChannelRow, onClose: () -> Unit, onForceClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface)
            .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    Fmt.shortId(channel.peer, 10, 6),
                    color = Pal.TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    channel.state,
                    color = if (channel.usable) Pal.Positive else Pal.TextSecondary,
                    fontSize = 11.sp
                )
            }
            Text(
                Fmt.sats(channel.capacitySats),
                color = Pal.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Can send", color = Pal.TextTertiary, fontSize = 10.sp)
                Text(Fmt.sats(channel.localSats), color = Pal.TextSecondary, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Can receive", color = Pal.TextTertiary, fontSize = 10.sp)
                Text(Fmt.sats(channel.remoteSats), color = Pal.TextSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Close",
                onClick = onClose,
                modifier = Modifier.weight(1f),
                enabled = channel.ready
            )
            GhostButton(
                text = "Force close",
                onClick = onForceClose,
                modifier = Modifier.weight(1f),
                danger = true
            )
        }
    }
}
