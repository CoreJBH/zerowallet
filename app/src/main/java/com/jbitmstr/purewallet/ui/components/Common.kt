package com.jbitmstr.purewallet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.purewallet.core.FiatQuote
import com.jbitmstr.purewallet.core.Fmt
import com.jbitmstr.purewallet.ui.theme.Pal

/** Fiat string for the balance card and rows, falling back to USD or a dash when offline. */
fun fiatText(sats: Long, quote: FiatQuote?): String {
    if (quote == null) return "—"
    return if (quote.krwPerBtc > 0.0) {
        Fmt.krw(Fmt.fiatFromSats(sats, quote.krwPerBtc))
    } else {
        val usd = quote.usdPerBtc ?: return "—"
        Fmt.usd(Fmt.fiatFromSats(sats, usd))
    }
}

@Composable
fun CardSurface(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Pal.Surface)
            .border(1.dp, Pal.Border, RoundedCornerShape(22.dp))
            .padding(padding),
        content = content
    )
}

@Composable
fun CircleIconButton(
    size: Dp = 56.dp,
    background: Color = Pal.SurfaceElevated,
    border: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, Pal.Border, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleIconButton(size = 64.dp, onClick = onClick) { icon() }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Pal.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WalletTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    // Words of a recovery phrase must never be learned or corrected by the keyboard.
    autoCorrectEnabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                color = Pal.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = Pal.TextTertiary, fontSize = 14.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Pal.TextPrimary,
                fontSize = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                autoCorrectEnabled = autoCorrectEnabled
            ),
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Pal.TextTertiary,
                unfocusedBorderColor = Pal.Border,
                disabledBorderColor = Pal.Border,
                focusedContainerColor = Pal.Surface,
                unfocusedContainerColor = Pal.Surface,
                disabledContainerColor = Pal.Surface,
                cursorColor = Pal.TextPrimary,
                focusedTextColor = Pal.TextPrimary,
                unfocusedTextColor = Pal.TextPrimary
            )
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled && !loading) Pal.Accent else Pal.SurfaceElevated)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Pal.OnAccent,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text,
                color = if (enabled) Pal.OnAccent else Pal.TextTertiary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.SurfaceElevated)
            .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (!enabled) Pal.TextTertiary else if (danger) Pal.Danger else Pal.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun Banner(
    title: String,
    body: String,
    tone: Color = Pal.Warning,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.SurfaceElevated)
            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, color = tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Pal.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            GhostButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun SegmentedOptions(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.Surface)
            .border(1.dp, Pal.Border, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) Pal.SurfaceElevated else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    color = if (active) Pal.TextPrimary else Pal.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun LabelRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Pal.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = Pal.TextPrimary,
            fontSize = 13.sp,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = Pal.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

@Composable
fun ThinDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Pal.Border)
    )
}

@Composable
fun SpacerRow(height: Dp) {
    Row(Modifier.fillMaxWidth()) { Spacer(Modifier.width(0.dp)) }
    Spacer(Modifier.height(height))
}

@Composable
fun SheetHeader(title: String, subtitle: String? = null, onClose: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Pal.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (onClose != null) {
            CircleIconButton(size = 36.dp, background = Pal.SurfaceElevated, onClick = onClose) {
                CloseGlyph(Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun OutlineBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Pal.Surface)
            .border(BorderStroke(1.dp, Pal.Border), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Shared shell for every bottom sheet: black canvas, hairline drag handle, scrollable
 * content that stays clear of the status bar, the keyboard and system navigation.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WalletSheet(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // Material's modal sheet only pads for the bottom system bar. A sheet with more content
    // than fits on the screen expands to the very top, so its "Settings"/"Channels" header
    // ended up under the status bar (clock, battery, camera cutout). The content height is
    // compared with the height the sheet has to work with and the top system inset is added
    // only when the content really reaches that far up, which leaves the compact sheets
    // (Send, Receive) exactly as they were.
    val density = LocalDensity.current
    val statusBarTop = androidx.compose.foundation.layout.WindowInsets.safeDrawing.getTop(density)
    var contentHeight by remember { mutableIntStateOf(0) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Pal.Background,
        contentColor = Pal.TextPrimary,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Pal.Border)
            )
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sheetHeightPx = with(density) { maxHeight.toPx() }.toInt()
            val reachesStatusBar = sheetHeightPx > 0 && contentHeight > sheetHeightPx - statusBarTop
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (reachesStatusBar) with(density) { statusBarTop.toDp() } else 0.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 24.dp)
            ) {
                Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }) {
                    content()
                }
            }
        }
    }
}
