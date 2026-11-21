package com.jbitmstr.purewallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deep navy palette: a Prussian blue canvas, a slightly lighter navy for cards, cobalt
 * accents and ice-blue type. Deep enough to stay dark at night, blue enough to feel like a
 * wallet.
 */
object Pal {
    /** Page canvas. */
    val Background = Color(0xFF060D24)

    /** Cards and fields that sit on the canvas. */
    val Surface = Color(0xFF0A1535)

    /** Raised surfaces: chips, selected rows, caption cards. */
    val SurfaceElevated = Color(0xFF0E1C47)

    /** Hairline borders around cards, fields and chips. */
    val Border = Color(0xFF1C2F66)

    val TextPrimary = Color(0xFFF1F5FF)
    val TextSecondary = Color(0xFF93A8D6)
    val TextTertiary = Color(0xFF5F73A6)

    /** Royal blue for primary actions and active highlights. */
    val Accent = Color(0xFF3B82F6)

    /** Cobalt for accents on top of the accent: focus rings, dots, links. */
    val AccentBright = Color(0xFF60A5FA)

    val Positive = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFF7A7A)

    /** Type on top of [Accent]. */
    val OnAccent = Color(0xFFF8FBFF)
}

private val DarkScheme = darkColorScheme(
    primary = Pal.Accent,
    onPrimary = Pal.OnAccent,
    primaryContainer = Pal.SurfaceElevated,
    onPrimaryContainer = Pal.TextPrimary,
    secondary = Pal.AccentBright,
    onSecondary = Pal.Background,
    secondaryContainer = Pal.SurfaceElevated,
    onSecondaryContainer = Pal.TextPrimary,
    tertiary = Pal.TextSecondary,
    onTertiary = Pal.Background,
    background = Pal.Background,
    onBackground = Pal.TextPrimary,
    surface = Pal.Surface,
    onSurface = Pal.TextPrimary,
    surfaceVariant = Pal.SurfaceElevated,
    onSurfaceVariant = Pal.TextSecondary,
    outline = Pal.Border,
    outlineVariant = Pal.Border,
    error = Pal.Danger,
    onError = Pal.Background
)

@Composable
fun PureWalletTheme(content: @Composable () -> Unit) {
    // The wallet is always dark; the system setting is intentionally ignored.
    @Suppress("UNUSED_VARIABLE")
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
