package com.jbitmstr.purewallet.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.core.QrCode
import com.jbitmstr.purewallet.core.SensitiveClipboard
import com.jbitmstr.purewallet.core.WalletState
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SegmentedOptions
import com.jbitmstr.purewallet.ui.components.SheetHeader
import com.jbitmstr.purewallet.ui.components.WalletSheet
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Receive sheet: BOLT11 invoice, BOLT12 offer or an on-chain address, each rendered as a
 * real QR code that can be copied or scanned by another wallet.
 */
@Composable
fun ReceiveSheet(
    vm: WalletViewModel,
    state: WalletState,
    onBackup: () -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun reset() {
        result = null
        qrContent = null
        error = null
    }

    fun copy(value: String) {
        // A payment request is meant to be handed to another app, but until it is pasted it
        // sits in the clipboard: mark it sensitive so Android does not draw it in the
        // clipboard preview where anyone standing there can read it.
        SensitiveClipboard.copy(context, value, "Copied", label = "ZeroWallet payment request")
    }

    WalletSheet(onDismiss = onClose) {
        SheetHeader(
            title = "Receive",
            subtitle = "Payment requests are created by your node on this phone.",
            onClose = onClose
        )

        if (!state.backedUp) {
            Banner(
                title = "Back up your recovery phrase first",
                body = "Funds sent to this wallet can only be recovered with your recovery phrase.",
                actionLabel = "Back up now",
                onAction = onBackup
            )
            Spacer(Modifier.height(16.dp))
        }

        SegmentedOptions(
            options = listOf("Lightning", "Offer", "On-chain"),
            selected = tab,
            onSelect = { tab = it; reset() }
        )

        Spacer(Modifier.height(20.dp))

        if (result == null) {
            if (tab != 2) {
                Text(
                    "Lightning payments need inbound liquidity: a channel whose remote balance is on the other side.",
                    color = Pal.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(14.dp))
                WalletTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() }; error = null },
                    placeholder = "Amount in sats (optional)",
                    label = "Amount",
                    keyboardType = KeyboardType.Number
                )
                Spacer(Modifier.height(14.dp))
                WalletTextField(
                    value = description,
                    onValueChange = { description = it; error = null },
                    placeholder = if (tab == 0) "What is this payment for?" else "Offer description",
                    label = "Description"
                )
                Spacer(Modifier.height(20.dp))
            } else {
                Text(
                    "Your node derives a fresh address from your own keys. On-chain funds arrive after one confirmation.",
                    color = Pal.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(18.dp))
            }

            if (error != null) {
                Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
            }

            PrimaryButton(
                text = when (tab) {
                    0 -> "Create Lightning invoice"
                    1 -> "Create offer"
                    else -> "Get on-chain address"
                },
                loading = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val amount = Fmt.parseSats(amountText)
                        val outcome = when (tab) {
                            0 -> vm.createInvoice(amount, description).map { it.invoice }
                            1 -> vm.createOffer(amount, description)
                            else -> vm.newAddress().map { address ->
                                if (amount != null) {
                                    "bitcoin:$address?amount=${Fmt.btc(amount)}"
                                } else {
                                    address
                                }
                            }
                        }
                        busy = false
                        outcome.onSuccess { value ->
                            result = if (tab == 2 && value.startsWith("bitcoin:")) value.substringAfter(':').substringBefore('?') else value
                            qrContent = value
                        }.onFailure { error = it.message ?: "Could not create the request" }
                    }
                }
            )
        } else {
            val content = qrContent ?: result.orEmpty()
            val bitmap = remember(content) { QrCode.bitmap(content, 640) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(20.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Payment QR code",
                        modifier = Modifier
                            .size(240.dp)
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(10.dp)
                    )
                } else {
                    Text("QR unavailable", color = Pal.TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = result.orEmpty(),
                    color = Pal.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                GhostButton(
                    text = "Copy",
                    onClick = { copy(content) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(10.dp))
                GhostButton(
                    text = "New request",
                    onClick = { reset() },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = when (tab) {
                    0 -> "The invoice expires in one hour. Keep the app open until the payment arrives."
                    1 -> "A BOLT12 offer stays reusable until you create a new one. Incoming BOLT12 payments need a reachable, announced node."
                    else -> "Only send Bitcoin on ${state.network.title} to this address."
                },
                color = Pal.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
