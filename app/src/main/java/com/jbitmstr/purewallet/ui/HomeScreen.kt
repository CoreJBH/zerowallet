package com.jbitmstr.purewallet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.core.ChainNetwork
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.PaymentRow
import com.jbitmstr.purewallet.core.WalletState
import com.jbitmstr.purewallet.ui.components.ActionButton
import com.jbitmstr.purewallet.ui.components.ArrowGlyph
import com.jbitmstr.purewallet.ui.components.BitcoinMark
import com.jbitmstr.purewallet.ui.components.BoltGlyph
import com.jbitmstr.purewallet.ui.components.CardSurface
import com.jbitmstr.purewallet.ui.components.ChevronGlyph
import com.jbitmstr.purewallet.ui.components.CircleIconButton
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.MenuGlyph
import com.jbitmstr.purewallet.ui.components.PlusGlyph
import com.jbitmstr.purewallet.ui.components.RefreshGlyph
import com.jbitmstr.purewallet.ui.components.ScanGlyph
import com.jbitmstr.purewallet.ui.components.SectionLabel
import com.jbitmstr.purewallet.ui.components.SwapGlyph
import com.jbitmstr.purewallet.ui.components.fiatText
import com.jbitmstr.purewallet.ui.theme.Pal

@Composable
fun HomeScreen(
    state: WalletState,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
    onRestart: () -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onChannels: () -> Unit,
    onSwap: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var dismissedError by rememberSaveable { mutableStateOf<String?>(null) }
    val completed = state.payments.filter { it.succeeded }
    val pending = state.payments.filter { it.status == "PENDING" }

    // A wallet that comes up again starts with a clean slate.
    LaunchedEffect(state.running) {
        if (state.running) dismissedError = null
    }
    val nodeError = state.error?.takeIf { !state.running && it != dismissedError }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 48.dp)
    ) {
        item { TopBar(state, onMenu, onRefresh) }

        if (nodeError != null) {
            item {
                Spacer(Modifier.height(16.dp))
                NodeErrorBanner(
                    message = nodeError,
                    starting = state.starting,
                    onRetry = {
                        dismissedError = null
                        onRestart()
                    },
                    onDismiss = { dismissedError = nodeError }
                )
            }
        }

        item { Spacer(Modifier.height(20.dp)) }

        item {
            BalanceCard(
                state = state,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onSwap = onSwap
            )
        }

        item { Spacer(Modifier.height(28.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ActionButton("Scan", onScan) { ScanGlyph(Modifier.size(24.dp)) }
                ActionButton("Send", onSend) { ArrowGlyph(Modifier.size(24.dp), up = true) }
                ActionButton("Receive", onReceive) { ArrowGlyph(Modifier.size(24.dp), up = false) }
                ActionButton("Channels", onChannels) { PlusGlyph(Modifier.size(24.dp)) }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item { SectionLabel("Completed") }

        if (pending.isNotEmpty()) {
            items(pending, key = { "pending-" + it.id }) { payment ->
                PaymentItem(payment, pending = true)
            }
        }

        if (completed.isEmpty() && pending.isEmpty()) {
            item { EmptyHistory() }
        }

        items(completed, key = { it.id }) { payment ->
            PaymentItem(payment, pending = false)
        }
    }
}

@Composable
private fun NodeErrorBanner(
    message: String,
    starting: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface)
            .border(1.dp, Pal.Danger.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            "The node is not running",
            color = Pal.Danger,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(message, color = Pal.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = if (starting) "Starting…" else "Try again",
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                enabled = !starting
            )
            GhostButton(
                text = "Dismiss",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TopBar(state: WalletState, onMenu: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onMenu),
            contentAlignment = Alignment.Center
        ) {
            MenuGlyph(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "ZeroWallet",
            color = Pal.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )
        if (!state.network.isMainnet) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Pal.SurfaceElevated)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(state.network.ticker, color = Pal.Warning, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center
        ) {
            if (state.syncing || state.starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Pal.TextSecondary,
                    strokeWidth = 1.5.dp
                )
            } else {
                RefreshGlyph(Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun BalanceCard(
    state: WalletState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSwap: () -> Unit
) {
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(
                size = 34.dp,
                background = Pal.SurfaceElevated,
                onClick = onToggle
            ) {
                ChevronGlyph(Modifier.size(13.dp), pointingUp = expanded)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    fiatText(state.totalSats, state.fiat),
                    color = Pal.TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    Fmt.sats(state.totalSats),
                    color = Pal.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(22.dp))
                BalanceRow(
                    icon = { BoltGlyph(Modifier.size(16.dp)) },
                    title = "Lightning",
                    subtitle = "BTC",
                    fiat = fiatText(state.lightningBalanceSats, state.fiat),
                    sats = Fmt.sats(state.lightningBalanceSats)
                )
                Spacer(Modifier.height(18.dp))
                BalanceRow(
                    icon = { BitcoinMark(size = 18.dp) },
                    title = "On-chain",
                    subtitle = "BTC",
                    fiat = fiatText(state.onchainTotalSats, state.fiat),
                    sats = Fmt.sats(state.onchainTotalSats),
                    trailing = {
                        CircleIconButton(
                            size = 38.dp,
                            background = Pal.SurfaceElevated,
                            onClick = onSwap
                        ) {
                            SwapGlyph(Modifier.size(16.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BalanceRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    fiat: String,
    sats: String,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Pal.SurfaceElevated),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Pal.TextTertiary, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fiat, color = Pal.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(sats, color = Pal.TextSecondary, fontSize = 12.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun PaymentItem(payment: PaymentRow, pending: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Pal.Surface),
            contentAlignment = Alignment.Center
        ) {
            if (payment.lightning) {
                BoltGlyph(Modifier.size(16.dp))
            } else {
                BitcoinMark(size = 18.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (payment.incoming) "You received" else "You sent",
                color = Pal.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            val sub = payment.description ?: payment.detail
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    color = Pal.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val sign = if (payment.incoming) "+" else "-"
            Text(
                sign + Fmt.sats(payment.amountSats),
                color = if (payment.incoming) Pal.Positive else Pal.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (pending) "Pending" else Fmt.date(payment.timestampSecs),
                color = Pal.TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No payments yet", color = Pal.TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Receive your first payment and it will show up here.",
            color = Pal.TextTertiary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun NetworkBadge(network: ChainNetwork, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Pal.SurfaceElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            network.title.uppercase(),
            color = Pal.Warning,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}
