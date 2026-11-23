package com.corejbh.zerowallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.zerowallet.ui.components.BoltGlyph
import com.corejbh.zerowallet.ui.components.PrimaryButton
import com.corejbh.zerowallet.ui.theme.Pal

/**
 * Full-window lock in front of the wallet. The system prompt is shown over it as soon as
 * the app opens; the button re-opens the prompt after a cancel or a failed attempt.
 */
@Composable
fun LockedScreen(
    error: String?,
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Pal.SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            BoltGlyph(Modifier.size(28.dp))
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "ZeroWallet is locked",
            color = Pal.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Unlock with your fingerprint or screen lock to open the wallet.",
            color = Pal.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                error,
                color = Pal.Danger,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(28.dp))

        Box(Modifier.widthIn(max = 320.dp)) {
            PrimaryButton(text = "Unlock", onClick = onUnlock)
        }
    }
}
