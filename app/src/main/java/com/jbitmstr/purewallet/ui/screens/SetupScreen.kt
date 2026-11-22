package com.jbitmstr.purewallet.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.core.Bip39
import com.jbitmstr.purewallet.core.SeedEntry
import com.jbitmstr.purewallet.ui.components.Banner
import com.jbitmstr.purewallet.ui.components.BoltGlyph
import com.jbitmstr.purewallet.ui.components.GhostButton
import com.jbitmstr.purewallet.ui.components.PrimaryButton
import com.jbitmstr.purewallet.ui.components.SegmentedOptions
import com.jbitmstr.purewallet.ui.theme.Pal
import kotlinx.coroutines.launch

/**
 * First run: create a wallet with one tap, or restore an existing recovery phrase.
 * Nothing else is asked, in the spirit of the reference wallet.
 *
 * Restoring uses one numbered box per word: a few letters finish a word on their own and the
 * caret jumps to the next box, suggestions sit right above the keyboard for the few words that
 * share a prefix, and a pasted phrase spreads over the boxes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    unlockError: Boolean = false,
    /** Why the stored wallet could not be opened, when [unlockError] is set. */
    unlockMessage: String? = null,
    onCreate: suspend (Int) -> Unit,
    onRestore: suspend (String) -> Result<Unit>
) {
    var mode by remember { mutableStateOf(if (unlockError) 1 else 0) }
    var createWordCount by remember { mutableStateOf(12) }
    var wordCount by remember { mutableStateOf(SeedEntry.MIN_WORDS) }
    var slots by remember { mutableStateOf(List(SeedEntry.SLOTS) { "" }) }
    var focusedBox by remember { mutableStateOf(0) }
    var jumpTo by remember { mutableStateOf(0) }
    var jumpTick by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val requesters = remember(wordCount) { List(wordCount) { FocusRequester() } }
    val viewers = remember(wordCount) { List(wordCount) { BringIntoViewRequester() } }

    // Auto-completion moves the caret while the user types, so the next box is requested here
    // rather than from inside a box that may already be leaving the composition.
    LaunchedEffect(jumpTick) {
        if (jumpTick == 0) return@LaunchedEffect
        val target = jumpTo.coerceIn(0, wordCount - 1)
        runCatching { requesters.getOrNull(target)?.requestFocus() }
        runCatching { viewers.getOrNull(target)?.bringIntoView() }
    }

    fun moveTo(box: Int) {
        jumpTo = box
        jumpTick++
    }

    val activeBox = focusedBox.coerceIn(0, wordCount - 1)
    val typed = slots.getOrElse(activeBox) { "" }
    val suggestions = if (mode == 1) Bip39.suggestWords(typed) else emptyList()
    val phrase = SeedEntry.phraseOrNull(slots.take(wordCount))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
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
                    body = unlockMessage
                        ?: "This phone still holds a wallet, but its key could not unlock it. " +
                        "Restore the wallet with your recovery phrase instead of creating a new one."
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
                    title = if (createWordCount == 24) "One tap, 24 words" else "One tap, 12 words",
                    body = "The phrase is generated from the phone's secure random generator " +
                        "(${if (createWordCount == 24) "256" else "128"} bits) and sealed with a key " +
                        "in the Android Keystore that only opens after you authenticate. You " +
                        "will see it once for backup."
                )
                Spacer(Modifier.height(16.dp))
                SegmentedOptions(
                    options = listOf("12 words", "24 words"),
                    selected = if (createWordCount == 24) 1 else 0,
                    onSelect = { createWordCount = if (it == 1) 24 else 12 }
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    text = if (creating) "Creating" else "Create wallet",
                    onClick = {
                        scope.launch {
                            // Generating the phrase and sealing it with a Keystore key takes
                            // long enough to be felt, so the button waits instead of the
                            // screen freezing.
                            creating = true
                            error = null
                            runCatching { onCreate(createWordCount) }
                                .onFailure { error = it.message ?: "Could not create the wallet" }
                            creating = false
                        }
                    },
                    enabled = !creating,
                    loading = creating
                )
            } else {
                Text(
                    "Recovery phrase",
                    color = Pal.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                SegmentedOptions(
                    options = listOf("12 words", "24 words"),
                    selected = if (wordCount == 24) 1 else 0,
                    onSelect = {
                        wordCount = if (it == 1) 24 else SeedEntry.MIN_WORDS
                        focusedBox = focusedBox.coerceAtMost(wordCount - 1)
                        error = null
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "One box per word. Three or four letters are enough to finish a word and jump " +
                        "to the next box; when several words share them, tap a suggestion. Pasting " +
                        "a whole phrase fills the boxes and switches to 24 words when it has to.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                )

                SeedGrid(
                    words = slots,
                    wordCount = wordCount,
                    requesters = requesters,
                    viewers = viewers,
                    onEdit = { box, new ->
                        val edit = SeedEntry.edit(slots, wordCount, box, new)
                        slots = edit.words
                        wordCount = edit.wordCount
                        error = null
                        if (edit.focus != box) {
                            focusedBox = edit.focus
                            moveTo(edit.focus)
                        }
                    },
                    onBoxFocused = { box -> focusedBox = box },
                    onBackspace = { box -> if (box > 0) moveTo(box - 1) },
                    onNext = { box ->
                        if (box + 1 < wordCount) {
                            focusedBox = box + 1
                            moveTo(box + 1)
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "Open channels are not part of the phrase. If you saved a channel backup, pick the " +
                        "file in Settings, under Lightning channel backup, once the wallet is open.",
                    color = Pal.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Mainnet by default. Test chains can be selected in Settings.",
                color = Pal.TextTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Start
            )
        }

        if (mode == 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            ) {
                if (error != null) {
                    Text(error ?: "", color = Pal.Danger, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton(
                    text = if (restoring) "Restoring" else "Restore wallet",
                    onClick = {
                        val words = phrase ?: return@PrimaryButton
                        scope.launch {
                            // Stopping the node and replacing its store happens off the main
                            // thread, so the button waits instead of the screen freezing.
                            restoring = true
                            error = null
                            onRestore(words).onFailure {
                                error = it.message ?: "Could not restore this phrase"
                            }
                            restoring = false
                        }
                    },
                    enabled = phrase != null && !restoring,
                    loading = restoring
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SuggestionBar(
                        box = activeBox,
                        words = suggestions,
                        onPick = { word ->
                            val edit = SeedEntry.pick(slots, wordCount, activeBox, word)
                            slots = edit.words
                            wordCount = edit.wordCount
                            error = null
                            if (edit.focus != activeBox) {
                                focusedBox = edit.focus
                                moveTo(edit.focus)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * The numbered word boxes: three compact columns, so 12 words fit in four rows and 24 in eight.
 * Each box holds exactly one BIP-39 word and jumps on as soon as its word is unambiguous.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeedGrid(
    words: List<String>,
    wordCount: Int,
    requesters: List<FocusRequester>,
    viewers: List<BringIntoViewRequester>,
    onEdit: (Int, String) -> Unit,
    onBoxFocused: (Int) -> Unit,
    onBackspace: (Int) -> Unit,
    onNext: (Int) -> Unit
) {
    val columns = 3
    val rows = (wordCount + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (column in 0 until columns) {
                    val box = row * columns + column
                    if (box < wordCount) {
                        SeedWordBox(
                            box = box,
                            word = words.getOrElse(box) { "" },
                            requester = requesters.getOrNull(box),
                            viewer = viewers.getOrNull(box),
                            onEdit = { new -> onEdit(box, new) },
                            onFocused = { onBoxFocused(box) },
                            onBackspace = { onBackspace(box) },
                            onNext = { onNext(box) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One numbered box for one word, with a caret that is only as wide as the word. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeedWordBox(
    box: Int,
    word: String,
    requester: FocusRequester?,
    viewer: BringIntoViewRequester?,
    onEdit: (String) -> Unit,
    onFocused: () -> Unit,
    onBackspace: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Pal.SurfaceElevated else Pal.Surface)
            .border(
                1.dp,
                if (focused) Pal.AccentBright else Pal.Border,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${box + 1}",
            color = if (focused) Pal.AccentBright else Pal.TextTertiary,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = word,
            onValueChange = onEdit,
            modifier = Modifier
                .weight(1f)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .then(if (viewer != null) Modifier.bringIntoViewRequester(viewer) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { event ->
                    // Deleting in an empty box steps back to the box before it.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && word.isEmpty()) {
                        onBackspace()
                        true
                    } else {
                        false
                    }
                },
            textStyle = TextStyle(
                color = Pal.TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            ),
            singleLine = true,
            cursorBrush = SolidColor(Pal.AccentBright),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(
                // A recovery phrase must never reach an IME's learned words, clipboard
                // history or suggestion strip, so it is typed into a password field: no
                // suggestions, no autocorrect, no personalised dictionary entry.
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() })
        )
    }
}

/**
 * Tappable BIP-39 words for the focused box, in one scrollable row so the bar stays compact
 * between the grid and the keyboard.
 */
@Composable
private fun SuggestionBar(box: Int, words: List<String>, onPick: (String) -> Unit) {
    val unique = words.size == 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.SurfaceElevated)
            .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            if (unique) {
                "Word ${box + 1}: one match, tap to finish"
            } else {
                "Word ${box + 1}: tap to complete"
            },
            color = Pal.TextTertiary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            words.forEach { word ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (unique) Pal.Surface else Pal.Background)
                        .border(
                            BorderStroke(1.dp, if (unique) Pal.AccentBright else Pal.Border),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onPick(word) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
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
}
