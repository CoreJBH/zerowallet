package com.corejbh.zerowallet.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.corejbh.zerowallet.BuildConfig

/**
 * Local descriptions of the invoices this wallet created, keyed by payment hash.
 *
 * ldk-node stores payments without the invoice description, so the wallet keeps its own
 * short note to show in the history list. Nothing here is shared with anyone, and the note
 * itself is sealed with [SecretStore] before it is written: "what did I pay this address
 * for" is as private as the payment. A note without the seal marker is refused rather than
 * returned. An earlier build read those notes back while it re-sealed them, and 1.2.0 removes
 * that window: a plaintext note is not a note this app wrote, and the description is the
 * least costly thing in the file to lose.
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
        val stored = prefs.all.keys.toList()
        if (stored.size > MAX_ENTRIES) {
            stored.take(stored.size - MAX_ENTRIES + 1).forEach { editor.remove(it) }
        }
        editor.putString(key, sealed).apply()
        return true
    }

    /** The note for [key], or null when there is none or it is not a sealed one. */
    fun get(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        val stored = prefs.getString(key, null) ?: return null
        if (!SecretStore.isSealed(stored)) return null
        return SecretStore.open(stored)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "PureWalletPaymentNotes"
        private const val NAME = "purewallet_payment_notes"
        private const val MAX_ENTRIES = 500
        private const val MAX_LENGTH = 140
    }
}
