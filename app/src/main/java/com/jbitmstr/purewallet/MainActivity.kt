package com.jbitmstr.purewallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jbitmstr.purewallet.ui.WalletApp
import com.jbitmstr.purewallet.ui.theme.PureWalletTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PureWalletTheme {
                WalletApp()
            }
        }
    }
}
