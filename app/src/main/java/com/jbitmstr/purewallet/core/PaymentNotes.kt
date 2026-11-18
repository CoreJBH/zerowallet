package com.jbitmstr.purewallet.core

import android.content.Context

/**
 * Local descriptions of the invoices this wallet created, keyed by payment hash.
 *
 * ldk-node stores payments without the invoice description, so the wallet keeps its own
 * short note to show in the history list. Nothing here is shared with anyone.
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
        editor.putString(key, note.trim().take(140)).apply()
    }

    fun get(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return prefs.getString(key, null)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
