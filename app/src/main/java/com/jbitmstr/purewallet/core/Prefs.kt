package com.jbitmstr.purewallet.core

import android.content.Context

/**
 * Non-secret wallet settings. The seed phrase never goes in here; it lives in [SecureStore].
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var network: ChainNetwork
        get() = ChainNetwork.fromId(prefs.getString(KEY_NETWORK, null))
        set(value) = prefs.edit().putString(KEY_NETWORK, value.id).apply()

    var seedBackedUp: Boolean
        get() = prefs.getBoolean(KEY_BACKED_UP, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKED_UP, value).apply()

    var rapidGossipSync: Boolean
        get() = prefs.getBoolean(KEY_RGS, false)
        set(value) = prefs.edit().putBoolean(KEY_RGS, value).apply()

    var walletCreatedAtEpochSecs: Long
        get() = prefs.getLong(KEY_CREATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CREATED_AT, value).apply()

    /** Custom Esplora endpoint for [network], or its built-in default. */
    fun esploraUrl(network: ChainNetwork): String {
        val stored = prefs.getString(KEY_ESPLORA + network.id, null)
        return if (stored.isNullOrBlank()) network.defaultEsploraUrl else stored.trim()
    }

    fun setEsploraUrl(network: ChainNetwork, url: String?) {
        val editor = prefs.edit()
        if (url.isNullOrBlank() || url.trim() == network.defaultEsploraUrl) {
            editor.remove(KEY_ESPLORA + network.id)
        } else {
            editor.putString(KEY_ESPLORA + network.id, url.trim())
        }
        editor.apply()
    }

    fun isCustomEsplora(network: ChainNetwork): Boolean =
        esploraUrl(network) != network.defaultEsploraUrl

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val NAME = "purewallet_settings"
        private const val KEY_NETWORK = "network"
        private const val KEY_BACKED_UP = "seed_backed_up"
        private const val KEY_RGS = "rapid_gossip_sync"
        private const val KEY_CREATED_AT = "wallet_created_at"
        private const val KEY_ESPLORA = "esplora_url_"
    }
}
