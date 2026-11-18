package com.jbitmstr.purewallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The wallet draws its own monochrome glyphs so the icon language stays consistent and
 * the APK does not need the full Material icon set.
 */
@Composable
fun MenuGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val x0 = size.width * 0.12f
        val x1 = size.width * 0.88f
        val strokeWidth = size.minDimension * 0.075f
        listOf(0.3f, 0.5f, 0.7f).forEach { y ->
            drawLine(color, Offset(x0, size.height * y), Offset(x1, size.height * y), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun RefreshGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round)
        val inset = size.minDimension * 0.18f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke
        )
        val arrow = Path().apply {
            moveTo(size.width * 0.78f, size.height * 0.02f)
            lineTo(size.width * 0.76f, size.height * 0.30f)
            lineTo(size.width * 0.50f, size.height * 0.14f)
            close()
        }
        drawPath(arrow, color)
    }
}

@Composable
fun ChevronGlyph(modifier: Modifier = Modifier, color: Color = Color.White, pointingUp: Boolean = true) {
    Canvas(modifier) {
        val strokeWidth = size.minDimension * 0.11f
        val baseY = if (pointingUp) size.height * 0.66f else size.height * 0.34f
        val midY = if (pointingUp) size.height * 0.34f else size.height * 0.66f
        drawLine(color, Offset(size.width * 0.2f, baseY), Offset(size.width * 0.5f, midY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, midY), Offset(size.width * 0.8f, baseY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
fun BoltGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.05f)
            lineTo(size.width * 0.25f, size.height * 0.55f)
            lineTo(size.width * 0.46f, size.height * 0.55f)
            lineTo(size.width * 0.40f, size.height * 0.95f)
            lineTo(size.width * 0.76f, size.height * 0.44f)
            lineTo(size.width * 0.54f, size.height * 0.44f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun ScanGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.085f
        val len = size.minDimension * 0.26f
        val inset = size.minDimension * 0.14f
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        // top-left
        drawLine(color, Offset(left, top + len), Offset(left, top), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(left + len, top), strokeWidth = stroke, cap = StrokeCap.Round)
        // top-right
        drawLine(color, Offset(right - len, top), Offset(right, top), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right, top + len), strokeWidth = stroke, cap = StrokeCap.Round)
        // bottom-left
        drawLine(color, Offset(left, bottom - len), Offset(left, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, bottom), Offset(left + len, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        // bottom-right
        drawLine(color, Offset(right - len, bottom), Offset(right, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - len), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun ArrowGlyph(modifier: Modifier = Modifier, color: Color = Color.White, up: Boolean = true) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val tipY = if (up) size.height * 0.16f else size.height * 0.84f
        val tailY = if (up) size.height * 0.86f else size.height * 0.14f
        drawLine(color, Offset(size.width * 0.5f, tailY), Offset(size.width * 0.5f, tipY), strokeWidth = stroke, cap = StrokeCap.Round)
        val wingY = if (up) size.height * 0.42f else size.height * 0.58f
        drawLine(color, Offset(size.width * 0.24f, wingY), Offset(size.width * 0.5f, tipY), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.76f, wingY), Offset(size.width * 0.5f, tipY), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun PlusGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.16f), Offset(size.width * 0.5f, size.height * 0.84f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.16f, size.height * 0.5f), Offset(size.width * 0.84f, size.height * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun SwapGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.08f
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.86f), Offset(size.width * 0.34f, size.height * 0.14f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.14f), Offset(size.width * 0.18f, size.height * 0.34f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.14f), Offset(size.width * 0.50f, size.height * 0.34f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.66f, size.height * 0.14f), Offset(size.width * 0.66f, size.height * 0.86f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.66f, size.height * 0.86f), Offset(size.width * 0.50f, size.height * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.66f, size.height * 0.86f), Offset(size.width * 0.82f, size.height * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun CloseGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawLine(color, Offset(size.width * 0.22f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.22f, size.height * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun BackGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawLine(color, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.32f, size.height * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun CopyGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
            size = Size(size.width * 0.52f, size.height * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.1f),
            style = stroke
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.34f, size.height * 0.34f),
            size = Size(size.width * 0.52f, size.height * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.1f),
            style = stroke
        )
    }
}

/** The Bitcoin mark, kept as type so it renders with the same weight as the text around it. */
@Composable
fun BitcoinMark(size: Dp = 22.dp, color: Color = Color.White) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = "₿",
            color = color,
            fontSize = (size.value * 0.95f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Lightning mark used in the balance rows and history. */
@Composable
fun LightningMark(size: Dp = 18.dp, color: Color = Color.White) {
    BoltGlyph(Modifier.size(size), color)
}
