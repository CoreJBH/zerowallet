package com.jbitmstr.purewallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.jbitmstr.purewallet.core.BackupTransfer
import com.jbitmstr.purewallet.core.ChannelBackup
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
 *
 * The channel backup file pickers are registered here too: the system picker (where Google
 * Drive shows up) backgrounds the app, and these launchers survive that even with lock-on-open.
 */
class MainActivity : FragmentActivity() {

    private val saveBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ChannelBackup.MIME_TYPE)
    ) { uri -> BackupTransfer.onSaveResult(this, uri) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> BackupTransfer.onOpenResult(this, uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenSecurity.apply(this, Prefs(this).screenProtection)
        BackupTransfer.bind(
            this,
            save = { fileName -> saveBackup.launch(fileName) },
            open = { openBackup.launch(arrayOf("*/*")) }
        )
        enableEdgeToEdge()
        setContent {
            PureWalletTheme {
                WalletApp()
            }
        }
    }

    override fun onDestroy() {
        // A rotation is not the end of the backup: the picker result comes back to the new
        // instance, which needs the export that was prepared for it.
        BackupTransfer.unbind(this, activityRecreating = isChangingConfigurations)
        super.onDestroy()
    }
}
