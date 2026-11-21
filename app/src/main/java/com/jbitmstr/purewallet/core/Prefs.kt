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

    /**
     * Ask for the fingerprint or screen lock when the app opens.
     *
     * Off by default: an opt-in in Settings, so nobody is locked out of their own wallet on
     * first run. Sending funds and showing the recovery phrase always authenticate on their
     * own, whether or not this is on.
     */
    var lockOnOpen: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ON_OPEN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ON_OPEN, value).apply()

    /** Block screenshots, screen recording and the recent-apps preview of the wallet. */
    var screenProtection: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_PROTECTION, value).apply()

    var walletCreatedAtEpochSecs: Long
        get() = prefs.getLong(KEY_CREATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CREATED_AT, value).apply()

    /**
     * True when the user pressed "Stop node". Kept on disk because Android may kill the
     * process: without it, the next open would quietly start a node the user had stopped.
     */
    var nodeStoppedByUser: Boolean
        get() = prefs.getBoolean(KEY_NODE_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_NODE_STOPPED, value).apply()

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
            setLastWorkingEsplora(network, null)
            return Result.success(Unit)
        }
        val validated = EsploraUrl.validate(trimmed)
        validated.onSuccess { clean ->
            editor.putString(KEY_ESPLORA + network.id, clean).apply()
            // A new endpoint is a new choice: stop putting the old working one in front of
            // the fallback list.
            setLastWorkingEsplora(network, null)
        }
        return validated.map { }
    }

    fun isCustomEsplora(network: ChainNetwork): Boolean =
        esploraUrl(network) != network.defaultEsploraUrl

    /**
     * The endpoint this chain last started on when it was not the saved one.
     *
     * A phone that cannot reach the built-in default is not made to wait for the same timeout
     * on every open: the endpoint that worked last is tried right after the saved one. Saving
     * an endpoint in Settings is a fresh choice, so it forgets this.
     */
    fun lastWorkingEsplora(network: ChainNetwork): String? {
        val stored = prefs.getString(KEY_ESPLORA_WORKING + network.id, null)
        if (stored.isNullOrBlank()) return null
        return EsploraUrl.validate(stored).getOrNull()
    }

    fun setLastWorkingEsplora(network: ChainNetwork, url: String?) {
        val editor = prefs.edit()
        if (url.isNullOrBlank() || url == esploraUrl(network)) {
            editor.remove(KEY_ESPLORA_WORKING + network.id)
        } else {
            editor.putString(KEY_ESPLORA_WORKING + network.id, url.trimEnd('/'))
        }
        editor.apply()
    }

    // ------------------------------------------------------- last known wallet numbers

    /**
     * The totals, height and history from this chain's last successful sync, or null when
     * nothing has been synced on it yet.
     */
    fun cachedWallet(network: ChainNetwork): CachedWallet? {
        val savedAt = prefs.getLong(KEY_CACHE_SAVED + network.id, 0L)
        if (savedAt <= 0L) return null
        return CachedWallet(
            onchainSats = prefs.getLong(KEY_CACHE_ONCHAIN + network.id, 0L),
            onchainSpendableSats = prefs.getLong(KEY_CACHE_SPENDABLE + network.id, 0L),
            lightningSats = prefs.getLong(KEY_CACHE_LIGHTNING + network.id, 0L),
            bestHeight = prefs.getInt(KEY_CACHE_HEIGHT + network.id, 0).takeIf { it > 0 },
            lastSyncEpochSecs = prefs.getLong(KEY_CACHE_SYNC + network.id, 0L)
                .takeIf { it > 0L },
            payments = WalletCache.decodePayments(
                prefs.getString(KEY_CACHE_PAYMENTS + network.id, null)
            )
        )
    }

    fun saveCachedWallet(network: ChainNetwork, cached: CachedWallet) {
        prefs.edit()
            .putLong(KEY_CACHE_SAVED + network.id, System.currentTimeMillis())
            .putLong(KEY_CACHE_ONCHAIN + network.id, cached.onchainSats)
            .putLong(KEY_CACHE_SPENDABLE + network.id, cached.onchainSpendableSats)
            .putLong(KEY_CACHE_LIGHTNING + network.id, cached.lightningSats)
            .putInt(KEY_CACHE_HEIGHT + network.id, cached.bestHeight ?: 0)
            .putLong(KEY_CACHE_SYNC + network.id, cached.lastSyncEpochSecs ?: 0L)
            .putString(KEY_CACHE_PAYMENTS + network.id, WalletCache.encodePayments(cached.payments))
            .apply()
    }

    /** Forgets a chain's snapshot: a different wallet, or a wipe, must not inherit it. */
    fun clearCachedWallet(network: ChainNetwork) {
        prefs.edit()
            .remove(KEY_CACHE_SAVED + network.id)
            .remove(KEY_CACHE_ONCHAIN + network.id)
            .remove(KEY_CACHE_SPENDABLE + network.id)
            .remove(KEY_CACHE_LIGHTNING + network.id)
            .remove(KEY_CACHE_HEIGHT + network.id)
            .remove(KEY_CACHE_SYNC + network.id)
            .remove(KEY_CACHE_PAYMENTS + network.id)
            .apply()
    }

    /**
     * One-time cleanup for the 1.1.4 default.
     *
     * Mainnet's built-in endpoint moved from mempool.space to Blockstream in 1.1.5 because
     * mempool.space answered the wallet's full scan with 429. A stored mainnet value that is
     * still that old default (only possible when it was saved with a trailing slash or other
     * letter case, since saving the default itself clears the key) is dropped once so the
     * faster endpoint is picked up. Anything saved after this update is a real choice and
     * is left alone.
     */
    fun migrateLegacyEsploraDefault() {
        if (prefs.getBoolean(KEY_ESPLORA_MIGRATED, false)) return
        val key = KEY_ESPLORA + ChainNetwork.MAINNET.id
        val stored = prefs.getString(key, null)
        if (stored != null && stored.trimEnd('/').equals(LEGACY_MAINNET_ESPLORA, ignoreCase = true)) {
            prefs.edit().remove(key).apply()
        }
        prefs.edit().putBoolean(KEY_ESPLORA_MIGRATED, true).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val NAME = "purewallet_settings"
        private const val KEY_NETWORK = "network"
        private const val KEY_BACKED_UP = "seed_backed_up"
        private const val KEY_RGS = "rapid_gossip_sync"
        private const val KEY_LOCK_ON_OPEN = "lock_on_open"
        private const val KEY_SCREEN_PROTECTION = "screen_protection"
        private const val KEY_CREATED_AT = "wallet_created_at"
        private const val KEY_NODE_STOPPED = "node_stopped_by_user"
        private const val KEY_ESPLORA = "esplora_url_"
        private const val KEY_ESPLORA_WORKING = "esplora_working_"
        private const val KEY_ESPLORA_MIGRATED = "esplora_default_migrated_1_1_5"
        private const val KEY_CACHE_SAVED = "cache_saved_at_"
        private const val KEY_CACHE_ONCHAIN = "cache_onchain_"
        private const val KEY_CACHE_SPENDABLE = "cache_spendable_"
        private const val KEY_CACHE_LIGHTNING = "cache_lightning_"
        private const val KEY_CACHE_HEIGHT = "cache_height_"
        private const val KEY_CACHE_SYNC = "cache_sync_"
        private const val KEY_CACHE_PAYMENTS = "cache_payments_"

        /** The mainnet endpoint that shipped up to 1.1.4. */
        private const val LEGACY_MAINNET_ESPLORA = "https://mempool.space/api"
    }
}
