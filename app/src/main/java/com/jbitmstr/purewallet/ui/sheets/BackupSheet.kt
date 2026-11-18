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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SheetHeader
import com.jbitmstr.purewallet.ui.components.WalletSheet
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal

/**
 * Shows the recovery phrase and, when the wallet is new, asks the user to retype two
 * words before the seed counts as backed up.
 */
@Composable
fun BackupSheet(
    mnemonic: String,
    requireVerification: Boolean,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    val words = remember(mnemonic) { mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val challenge = remember(words) {
        if (words.size >= 8) listOf(2, 7) else listOf(0, words.size - 1)
    }
    var step by remember { mutableIntStateOf(0) }
    var firstAnswer by remember { mutableStateOf("") }
    var secondAnswer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    WalletSheet(onDismiss = onDismiss) {
        SheetHeader(
            title = if (step == 0) "Recovery phrase" else "Confirm your backup",
            subtitle = if (step == 0) {
                "Write these ${words.size} words down in order and keep them offline."
            } else {
                "Type the words below to prove the backup is complete."
            },
            onClose = onDismiss
        )

        if (step == 0) {
            Banner(
                title = "Keep this private",
                body = "Anyone who has these words can spend your bitcoin. PUREWALLET cannot recover them for you."
            )

            Spacer(Modifier.height(18.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Pal.Surface)
                    .border(1.dp, Pal.Border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                val half = (words.size + 1) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        words.take(half).forEachIndexed { index, word ->
                            WordLine(index + 1, word)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        words.drop(half).forEachIndexed { index, word ->
                            WordLine(half + index + 1, word)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            PrimaryButton(
                text = if (requireVerification) "I wrote these words down" else "Done",
                onClick = {
                    if (requireVerification) {
                        step = 1
                    } else {
                        onConfirmed()
                        onDismiss()
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            if (requireVerification) {
                GhostButton(
                    text = "Skip for now",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Skipping leaves the wallet unverified. You will be reminded before receiving funds.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        } else {
            Text(
                "Word #${challenge[0] + 1}",
                color = Pal.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            WalletTextField(
                value = firstAnswer,
                onValueChange = { firstAnswer = it; error = null },
                placeholder = "Type word ${challenge[0] + 1}",
                keyboardType = KeyboardType.Text
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Word #${challenge[1] + 1}",
                color = Pal.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            WalletTextField(
                value = secondAnswer,
                onValueChange = { secondAnswer = it; error = null },
                placeholder = "Type word ${challenge[1] + 1}",
                keyboardType = KeyboardType.Text
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                text = "Confirm backup",
                onClick = {
                    val expectedFirst = words[challenge[0]].lowercase()
                    val expectedSecond = words[challenge[1]].lowercase()
                    if (firstAnswer.trim().lowercase() == expectedFirst &&
                        secondAnswer.trim().lowercase() == expectedSecond
                    ) {
                        onConfirmed()
                        onDismiss()
                    } else {
                        error = "Those words do not match. Check the phrase and try again."
                    }
                },
                enabled = firstAnswer.isNotBlank() && secondAnswer.isNotBlank()
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Show the words again",
                onClick = { step = 0 },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WordLine(index: Int, word: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            index.toString().padStart(2, '0'),
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(10.dp))
        Text(
            word,
            color = Pal.TextPrimary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
