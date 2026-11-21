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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.FeePriority
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.PaymentInputKind
import com.jbitmstr.purewallet.core.PaymentInputParser
import com.jbitmstr.purewallet.core.ResolvedTarget
import com.jbitmstr.purewallet.core.WalletState
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.LabelRow
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SegmentedOptions
import com.jbitmstr.purewallet.ui.components.SheetHeader
import com.jbitmstr.purewallet.ui.components.WalletSheet
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal
import kotlinx.coroutines.launch

/**
 * Send sheet: paste or scan a BOLT11 invoice, BOLT12 offer/invoice or a BIP-21 address,
 * review the amount and fee, then sign and broadcast from the node on this phone.
 */
@Composable
fun SendSheet(
    vm: WalletViewModel,
    state: WalletState,
    initialText: String,
    onAuthorize: (String, () -> Unit) -> Unit,
    onClose: () -> Unit,
    onScan: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var target by remember { mutableStateOf<ResolvedTarget?>(null) }
    var amountText by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(FeePriority.NORMAL) }
    var sendAll by remember { mutableStateOf(false) }
    var feeRate by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(initialText) {
        if (initialText.isNotBlank()) {
            text = initialText
            target = null
            reference = null
        }
    }

    val onchain = target?.input?.kind == PaymentInputKind.ONCHAIN
    val needsAmount = target?.amountEditable == true

    LaunchedEffect(onchain, priority) {
        if (onchain) {
            feeRate = vm.estimateFeeRate(priority)
        }
    }

    fun review() {
        val parsed = PaymentInputParser.parse(text)
        if (parsed.kind == PaymentInputKind.PEER) {
            error = "That is a Lightning peer address. Use Channels to connect to it."
            return
        }
        if (parsed.kind == PaymentInputKind.UNKNOWN) {
            error = "That does not look like an invoice, offer or Bitcoin address."
            return
        }
        scope.launch {
            busy = true
            error = null
            reference = null
            vm.resolveTarget(parsed).onSuccess { resolved ->
                target = resolved
                amountText = resolved.amountSats?.toString() ?: ""
                sendAll = false
            }.onFailure {
                target = null
                error = it.message ?: "Could not read this payment request"
            }
            busy = false
        }
    }

    fun execute() {
        val current = target ?: return
        val amount = Fmt.parseSats(amountText)
        if (current.amountEditable && amount == null && !sendAll) {
            error = "Enter an amount in sats"
            return
        }
        onAuthorize("Authenticate to send this payment.") {
            scope.launch {
                busy = true
                error = null
                val outcome = if (current.input.kind == PaymentInputKind.ONCHAIN && sendAll) {
                    vm.sendOnchain(
                        address = current.input.address.orEmpty(),
                        amountSats = null,
                        sendAll = true,
                        priority = priority
                    )
                } else {
                    vm.pay(current, amount, priority)
                }
                outcome.onSuccess { reference = it }.onFailure {
                    error = it.message ?: "The payment could not be sent"
                }
                busy = false
            }
        }
    }

    WalletSheet(onDismiss = onClose) {
        SheetHeader(
            title = "Send",
            subtitle = "Pay from your own node. Nothing is routed through a custodian.",
            onClose = onClose
        )

        if (reference != null) {
            Banner(
                title = "Payment sent",
                body = "The node signed and broadcast this payment. It appears in your history as soon as the network confirms it.",
                tone = Pal.Positive
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        if (target?.input?.kind == PaymentInputKind.ONCHAIN) "Transaction id" else "Payment id",
                        color = Pal.TextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        reference.orEmpty(),
                        color = Pal.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(text = "Done", onClick = onClose)
            return@WalletSheet
        }

        WalletTextField(
            value = text,
            onValueChange = { text = it; target = null; error = null },
            placeholder = "Invoice, offer or Bitcoin address",
            label = "Payment request",
            singleLine = false,
            minLines = 3
        )

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Paste",
                onClick = {
                    clipboard.getText()?.text?.let {
                        text = it
                        target = null
                        error = null
                    }
                },
                modifier = Modifier.weight(1f)
            )
            GhostButton(
                text = "Scan",
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error ?: "", color = Pal.Danger, fontSize = 12.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(18.dp))

        if (target == null) {
            PrimaryButton(
                text = "Review payment",
                onClick = { review() },
                enabled = text.isNotBlank(),
                loading = busy
            )
        } else {
            val current = target ?: return@WalletSheet
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                LabelRow("Type", kindLabel(current.input.kind))
                if (current.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    LabelRow("Description", current.description)
                }
                if (current.input.kind == PaymentInputKind.ONCHAIN) {
                    Spacer(Modifier.height(8.dp))
                    LabelRow(
                        "Address",
                        Fmt.shortId(current.input.address.orEmpty(), 10, 8),
                        mono = true
                    )
                }
                if (state.fiat != null && current.amountSats != null) {
                    Spacer(Modifier.height(8.dp))
                    LabelRow(
                        "Value",
                        com.jbitmstr.purewallet.ui.components.fiatText(current.amountSats, state.fiat)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (needsAmount) {
                WalletTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
                    placeholder = "Amount in sats",
                    label = "Amount",
                    keyboardType = KeyboardType.Number,
                    enabled = !sendAll
                )
            } else {
                Column {
                    Text("Amount", color = Pal.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Fmt.sats(current.amountSats ?: 0L),
                        color = Pal.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (onchain) {
                Spacer(Modifier.height(16.dp))
                Text("Fee", color = Pal.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                SegmentedOptions(
                    options = listOf("Normal", "Fast"),
                    selected = if (priority == FeePriority.NORMAL) 0 else 1,
                    onSelect = { priority = if (it == 0) FeePriority.NORMAL else FeePriority.FAST }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Network fee rate: ${feeRate ?: "…"} sat/vB. The exact fee is set by the transaction size.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = if (sendAll) "Send all (selected)" else "Send all on-chain balance",
                    onClick = { sendAll = !sendAll; if (sendAll) amountText = "" },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (onchain) "Send on-chain" else "Send payment",
                onClick = { execute() },
                loading = busy,
                enabled = !state.starting
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Use a different request",
                onClick = {
                    target = null
                    reference = null
                    error = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun kindLabel(kind: PaymentInputKind): String = when (kind) {
    PaymentInputKind.BOLT11 -> "Lightning invoice (BOLT11)"
    PaymentInputKind.BOLT12_OFFER -> "Lightning offer (BOLT12)"
    PaymentInputKind.BOLT12_INVOICE -> "Lightning invoice (BOLT12)"
    PaymentInputKind.ONCHAIN -> "On-chain Bitcoin"
    PaymentInputKind.PEER -> "Lightning peer"
    PaymentInputKind.UNKNOWN -> "Unknown"
}
