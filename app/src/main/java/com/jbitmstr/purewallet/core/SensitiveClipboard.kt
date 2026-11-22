package com.jbitmstr.purewallet.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

/**
 * Copies a wallet value (an invoice, address, offer or node id) to the clipboard and marks
 * it as sensitive.
 *
 * Android 13 and later keep a sensitive clip out of the clipboard preview in the corner of
 * the screen and out of the "just copied" toast, so an address on its way to another wallet
 * is not flashed on screen for anyone nearby. Older versions ignore the flag, which is why
 * the app's own toast still says what was copied.
 *
 * The value is a [ClipData] rather than a plain string on purpose: [ClipboardManager] can
 * only carry the sensitive flag on a clip whose description it owns.
 */
object SensitiveClipboard {

    /** Copies [text] and shows [toast] on the current screen. */
    fun copy(context: Context, text: String, toast: String, label: String = "ZeroWallet") {
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // EXTRA_IS_SENSITIVE is API 33; older platforms simply have no such extra.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, "The clipboard is not available right now", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { manager.setPrimaryClip(clip) }
            .onFailure {
                Toast.makeText(context, "Could not copy to the clipboard", Toast.LENGTH_SHORT).show()
                return
            }
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}
