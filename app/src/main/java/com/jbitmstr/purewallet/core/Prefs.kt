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

    /** Ask for the fingerprint or screen lock when the app opens and before spending. */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** Block screenshots, screen recording and the recent-apps preview of the wallet. */
    var screenProtection: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_PROTECTION, value).apply()

    var walletCreatedAtEpochSecs: Long
        get() = prefs.getLong(KEY_CREATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CREATED_AT, value).apply()

    /**
     * Custom Esplora endpoint for [network], or its built-in default. A stored value that
     * no longer passes validation (an old plain-http entry, for example) is ignored, so
     * the node never starts against an endpoint the user did not mean to trust.
     */
    fun esploraUrl(network: ChainNetwork): String {
        val stored = prefs.getString(KEY_ESPLORA + network.id, null)
        if (stored.isNullOrBlank()) return network.defaultEsploraUrl
        return EsploraUrl.validate(stored).getOrElse { network.defaultEsploraUrl }
    }

    /**
     * Saves a custom endpoint, or clears it when the value is blank or the default.
     * Fails with a user-facing message when [url] is not an acceptable endpoint.
     */
    fun setEsploraUrl(network: ChainNetwork, url: String?): Result<Unit> {
        val trimmed = url?.trim().orEmpty()
        val editor = prefs.edit()
        if (trimmed.isEmpty() || trimmed == network.defaultEsploraUrl) {
            editor.remove(KEY_ESPLORA + network.id).apply()
            return Result.success(Unit)
        }
        val validated = EsploraUrl.validate(trimmed)
        validated.onSuccess { clean ->
            editor.putString(KEY_ESPLORA + network.id, clean).apply()
        }
        return validated.map { }
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
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_SCREEN_PROTECTION = "screen_protection"
        private const val KEY_CREATED_AT = "wallet_created_at"
        private const val KEY_ESPLORA = "esplora_url_"
    }
}
