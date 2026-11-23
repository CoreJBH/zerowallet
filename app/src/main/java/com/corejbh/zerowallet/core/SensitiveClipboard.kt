package com.corejbh.zerowallet.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * The clip is also taken back after [CLEAR_DELAY_MS]. The system eventually clears the
 * clipboard on its own, but "eventually" is long enough for a pasted address to be sitting
 * there for the next app that reads it - and a wallet address swapped in the clipboard
 * between the copy and the paste is exactly how a payment goes to the wrong place. A timer
 * works on every version this app supports, the same way on each, instead of relying on a
 * newer platform's housekeeping.
 *
 * The value is a [ClipData] rather than a plain string on purpose: [ClipboardManager] can
 * only carry the sensitive flag on a clip whose description it owns.
 */
object SensitiveClipboard {

    /** How long a copied wallet value stays on the clipboard. */
    const val CLEAR_DELAY_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())

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
        scheduleClear(context.applicationContext, text)
    }

    /**
     * Clears the clipboard after the delay, and only while it still holds this copy.
     *
     * The application context is used rather than the screen that asked: the timer outlives
     * the sheet, and a wallet value must not stay put because the user closed the screen it
     * was copied from - which is exactly when they are pasting it somewhere else. A clip the
     * user has replaced in the meantime is left alone: it is theirs now, and this class has no
     * business emptying it.
     */
    private fun scheduleClear(context: Context, copied: String) {
        handler.postDelayed({
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@postDelayed
            val current = runCatching {
                manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()
            if (!shouldClear(current, copied)) return@postDelayed
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.clearPrimaryClip()
                } else {
                    // clearPrimaryClip() is API 28; on 26-27 an empty clip is the way to take
                    // the value back without leaving a copy behind.
                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLEAR_DELAY_MS)
    }

    /**
     * True when the clipboard should be taken back after the delay.
     *
     * A readable clipboard is compared with [copied]: the value this class put there goes,
     * anything the user copied since stays. Null is a clipboard this app cannot read - it is
     * in the background, which is the usual case two minutes after a copy, and Android 10 and
     * later hide the clipboard from a process without focus. That is treated as "still ours"
     * on purpose: the value on it is either this one, or one this app cannot see and therefore
     * cannot be sure is not this one, and a wallet address left on the clipboard for the next
     * app that paste-watches it is the failure this timer exists to prevent.
     */
    internal fun shouldClear(current: String?, copied: String): Boolean =
        current.isNullOrEmpty() || current == copied
}
