package com.jbitmstr.purewallet.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.core.Bip39
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.BoltGlyph
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SegmentedOptions
import com.jbitmstr.purewallet.ui.components.WalletTextField
import com.jbitmstr.purewallet.ui.theme.Pal

/**
 * First run: create a wallet with one tap, or restore an existing recovery phrase.
 * Nothing else is asked, in the spirit of the reference wallet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    unlockError: Boolean = false,
    onCreate: (Int) -> Unit,
    onRestore: (String) -> Result<Unit>
) {
    var mode by remember { mutableStateOf(if (unlockError) 1 else 0) }
    var wordCount by remember { mutableStateOf(12) }
    var phrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Pal.SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            BoltGlyph(Modifier.size(26.dp))
        }

        Spacer(Modifier.height(24.dp))

        Text("ZeroWallet", color = Pal.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "A self-custodial Lightning and on-chain wallet that runs entirely on this phone. No account, no server, no middleman.",
            color = Pal.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        if (unlockError) {
            Spacer(Modifier.height(24.dp))
            Banner(
                title = "Wallet locked",
                body = "This phone still holds a wallet, but its key could not unlock it. Restore the wallet with your recovery phrase instead of creating a new one."
            )
        }

        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth()) {
            if (!unlockError) {
                GhostButton(
                    text = "Create new wallet",
                    onClick = { mode = 0; error = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(10.dp))
            }
            GhostButton(
                text = "Restore wallet",
                onClick = { mode = 1; error = null },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(26.dp))

        if (mode == 0) {
            Banner(
                title = if (wordCount == 24) "One tap, 24 words" else "One tap, 12 words",
                body = "The phrase is generated from the phone's secure random generator " +
                    "(${if (wordCount == 24) "256" else "128"} bits) and encrypted with a key in the " +
                    "Android Keystore. You will see it once for backup."
            )
            Spacer(Modifier.height(16.dp))
            SegmentedOptions(
                options = listOf("12 words", "24 words"),
                selected = if (wordCount == 24) 1 else 0,
                onSelect = { wordCount = if (it == 1) 24 else 12 }
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = "Create wallet", onClick = { onCreate(wordCount) })
        } else {
            Text(
                "Recovery phrase",
                color = Pal.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            WalletTextField(
                value = phrase,
                onValueChange = { phrase = it; error = null },
                placeholder = "Enter your 12 or 24 words, separated by spaces",
                keyboardType = KeyboardType.Text,
                singleLine = false,
                minLines = 4
            )

            // BIP-39 suggestions for the word being typed. A four-letter prefix already
            // identifies one word, so a single tap finishes it and moves on to the next.
            val typedWord = Bip39.currentWord(phrase)
            val suggestions = Bip39.suggestWords(typedWord)
            val chipRequester = remember { BringIntoViewRequester() }
            LaunchedEffect(suggestions) {
                // Keep the chips above the keyboard while the phrase is being typed.
                if (suggestions.isNotEmpty()) chipRequester.bringIntoView()
            }
            if (suggestions.isNotEmpty()) {
                val wordIndex = if (phrase.isBlank()) 1 else Bip39.wordCount(phrase)
                Column(Modifier.bringIntoViewRequester(chipRequester)) {
                    Text(
                        "Word $wordIndex: tap to complete",
                        color = Pal.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp)
                    )
                    WordSuggestions(
                        words = suggestions,
                        unique = suggestions.size == 1,
                        onPick = { word ->
                            phrase = Bip39.withCompletedWord(phrase, word)
                            error = null
                        }
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Restore wallet",
                onClick = {
                    val result = onRestore(phrase)
                    result.onFailure { error = it.message ?: "Could not restore this phrase" }
                },
                enabled = phrase.isNotBlank()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Open channels are not part of the phrase. If you saved a channel backup, pick the " +
                    "file in Settings, under Lightning channel backup, once the wallet is open.",
                color = Pal.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Mainnet by default. Test chains can be selected in Settings.",
            color = Pal.TextTertiary,
            fontSize = 11.sp,
            textAlign = TextAlign.Start
        )
    }
}

/** Wrapping row of tappable BIP-39 words that complete the half-typed word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordSuggestions(
    words: List<String>,
    unique: Boolean,
    onPick: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        words.forEach { word ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (unique) Pal.SurfaceElevated else Pal.Surface)
                    .border(
                        BorderStroke(1.dp, if (unique) Pal.TextTertiary else Pal.Border),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onPick(word) }
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(
                    word,
                    color = Pal.TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (unique) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
