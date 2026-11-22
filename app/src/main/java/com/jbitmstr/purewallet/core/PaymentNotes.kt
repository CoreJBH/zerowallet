package com.jbitmstr.purewallet.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jbitmstr.purewallet.BuildConfig

/**
 * Local descriptions of the invoices this wallet created, keyed by payment hash.
 *
 * ldk-node stores payments without the invoice description, so the wallet keeps its own
 * short note to show in the history list. Nothing here is shared with anyone, and the note
 * itself is sealed with [SecretStore] before it is written: "what did I pay this address
 * for" is as private as the payment. A note written by an older build is plain text without
 * the seal marker; [migratePlainTextNotes] seals those once, and until it has finished they
 * still read back. Afterwards a plain value is refused: nothing this build writes lacks the
 * marker.
 *
 * [put] is fail-closed like the balance cache: a Keystore that cannot seal the note means
 * the note is not saved at all. A missing description is a small loss; the same sentence in
 * plain text is the thing the seal was added to prevent.
 *
 * The sealer is a constructor argument so that path can be tested without Android.
 */
class PaymentNotes internal constructor(
    private val prefs: SharedPreferences,
    private val seal: (String) -> String?
) {

    constructor(context: Context) : this(
        context.applicationContext
            .getSharedPreferences(NAME, Context.MODE_PRIVATE),
        SecretStore::seal
    )

    /** Saves a note, or nothing when it cannot be sealed. False means it was not written. */
    fun put(key: String, note: String): Boolean {
        if (key.isEmpty() || note.isBlank()) return false
        val trimmed = note.trim().take(MAX_LENGTH)
        val sealed = seal(trimmed) ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "A payment note could not be sealed and was not saved")
            return false
        }
        val editor = prefs.edit()
        // The migration marker is a record of this file's state, not a note: it is never the
        // one dropped to make room.
        val stored = prefs.all.keys.filter { it != KEY_PLAIN_TEXT_MIGRATED }
        if (stored.size > MAX_ENTRIES) {
            stored.take(stored.size - MAX_ENTRIES + 1).forEach { editor.remove(it) }
        }
        editor.putString(key, sealed).apply()
        return true
    }

    fun get(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        val stored = prefs.getString(key, null) ?: return null
        return SecretStore.open(stored, allowPlainText = plainTextStillReadable())
    }

    /**
     * Seals every note an older build wrote in plain text, once.
     *
     * The marker is written only when every note could be sealed. A Keystore that refuses one
     * now leaves the migration for the next start rather than throwing the note away.
     */
    fun migratePlainTextNotes() {
        if (!plainTextStillReadable()) return
        val editor = prefs.edit()
        var clean = true
        prefs.all.forEach { (key, value) ->
            val text = value as? String ?: return@forEach
            if (SecretStore.isSealed(text)) return@forEach
            val sealed = seal(text)
            if (sealed != null) editor.putString(key, sealed) else clean = false
        }
        if (clean) editor.putBoolean(KEY_PLAIN_TEXT_MIGRATED, true)
        editor.apply()
    }

    private fun plainTextStillReadable(): Boolean =
        !prefs.getBoolean(KEY_PLAIN_TEXT_MIGRATED, false)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "PureWalletPaymentNotes"
        private const val NAME = "purewallet_payment_notes"
        private const val MAX_ENTRIES = 500
        private const val MAX_LENGTH = 140

        /** Set when every note on this phone is a sealed one; see [migratePlainTextNotes]. */
        private const val KEY_PLAIN_TEXT_MIGRATED = "plain_notes_migrated_1_1_18"
    }
}
