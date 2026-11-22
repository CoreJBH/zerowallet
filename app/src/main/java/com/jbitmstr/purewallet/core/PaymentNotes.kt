package com.jbitmstr.purewallet.core

import android.content.Context

/**
 * Local descriptions of the invoices this wallet created, keyed by payment hash.
 *
 * ldk-node stores payments without the invoice description, so the wallet keeps its own
 * short note to show in the history list. Nothing here is shared with anyone, and the note
 * itself is sealed with [SecretStore] before it is written: "what did I pay this address
 * for" is as private as the payment. A note written by an older build is plain text without
 * the seal marker, still reads back, and is replaced by a sealed one the next time it is set
 * for that payment.
 */
class PaymentNotes(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("purewallet_payment_notes", Context.MODE_PRIVATE)

    fun put(key: String, note: String) {
        if (key.isEmpty() || note.isBlank()) return
        val editor = prefs.edit()
        if (prefs.all.size > MAX_ENTRIES) {
            prefs.all.keys.take(prefs.all.size - MAX_ENTRIES + 1).forEach { editor.remove(it) }
        }
        val trimmed = note.trim().take(MAX_LENGTH)
        editor.putString(key, SecretStore.seal(trimmed) ?: trimmed).apply()
    }

    fun get(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        val stored = prefs.getString(key, null) ?: return null
        return SecretStore.open(stored)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_LENGTH = 140
    }
}
