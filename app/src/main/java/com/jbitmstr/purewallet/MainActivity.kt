package com.jbitmstr.purewallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.jbitmstr.purewallet.core.Prefs
import com.jbitmstr.purewallet.core.ScreenSecurity
import com.jbitmstr.purewallet.ui.WalletApp
import com.jbitmstr.purewallet.ui.theme.PureWalletTheme

/**
 * Single wallet window.
 *
 * A [FragmentActivity] because the app lock needs `BiometricPrompt`, which hangs off the
 * fragment manager. Screenshot blocking is applied here before the first frame so the
 * wallet never appears in a Recents thumbnail, and kept in sync with the setting from
 * Compose.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenSecurity.apply(this, Prefs(this).screenProtection)
        enableEdgeToEdge()
        setContent {
            PureWalletTheme {
                WalletApp()
            }
        }
    }
}
