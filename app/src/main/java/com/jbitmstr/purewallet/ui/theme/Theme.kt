package com.jbitmstr.purewallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** True Black palette: pure black canvas, near-black elevated surfaces, crisp white type. */
object Pal {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF121417)
    val SurfaceElevated = Color(0xFF181A1F)
    val Border = Color(0xFF23272F)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8A8F98)
    val TextTertiary = Color(0xFF5C626D)
    val Positive = Color(0xFF3DD68C)
    val Warning = Color(0xFFF5A524)
    val Danger = Color(0xFFFF6B6B)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Pal.SurfaceElevated,
    onPrimaryContainer = Pal.TextPrimary,
    secondary = Pal.TextSecondary,
    onSecondary = Color(0xFF000000),
    background = Pal.Background,
    onBackground = Pal.TextPrimary,
    surface = Pal.Surface,
    onSurface = Pal.TextPrimary,
    surfaceVariant = Pal.SurfaceElevated,
    onSurfaceVariant = Pal.TextSecondary,
    outline = Pal.Border,
    outlineVariant = Pal.Border,
    error = Pal.Danger,
    onError = Color(0xFF000000)
)

@Composable
fun PureWalletTheme(content: @Composable () -> Unit) {
    // The wallet is always dark; the system setting is intentionally ignored.
    @Suppress("UNUSED_VARIABLE")
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
