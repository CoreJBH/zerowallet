package com.corejbh.zerowallet.core

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import com.corejbh.zerowallet.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the seed phrase with AES-256-GCM and a key that is generated inside the Android
 * Keystore, so the key material never exists outside the Keystore - not even for the moment
 * an import would need.
 *
 * One key serves both directions ([KEY_ALIAS]). On Android 8 and later on a phone with a
 * screen lock it is created with `setUserAuthenticationRequired(true)` and a short validity
 * window, so the Keystore itself refuses to use it until the user has authenticated on the
 * device (screen unlock, or the prompt this wallet shows). Knowing the app's code, or
 * getting a shell into it, is not enough to open the phrase. That binding uses a window
 * instead of a per-operation `CryptoObject`, on purpose: the node reads the phrase on its
 * own when the app comes back to the screen, and a per-use key would need a prompt around
 * every single read.
 *
 * A bound key is used for writing too, so a seal can land outside the window: the Keystore
 * then throws [UserNotAuthenticatedException] and [saveSeed] answers
 * [SeedSeal.NeedsAuthentication] instead of writing anything. The screen that is creating or
 * restoring a wallet asks for the device lock and calls it again (see `WalletApp`). Nothing
 * is ever written with a weaker key to get around that, and the caller learns the difference
 * between "authenticate and retry" and "this phone cannot seal at all".
 *
 * The one fallback that remains is for a phone that *cannot* bind: no screen lock at all.
 * There the key is still generated inside the Keystore and never leaves it, but it opens
 * without the device lock. That is reported as [SeedProtection.UNBOUND] with the reason,
 * shown on the home screen and in Settings - it is a state the user is told about, not one to
 * be assumed away. A phone that *has* a lock but whose Keystore refuses the bound key is
 * different: nothing is generated, and the caller is told the phrase was not stored
 * ([KeyBindingRefused]) rather than being handed an unbound key as if it were the real
 * thing.
 *
 * Lock changes: the Keystore invalidates a bound key when the secure lock screen is removed
 * or reset (a normal PIN change keeps it usable, and enrolling a new fingerprint does not
 * invalidate it - see [KEY_ALIAS]'s spec). [readSeed] reports that as
 * [SeedRead.Invalidated] instead of a vague failure, so the app can say what happened and
 * send the user to the recovery phrase rather than silently losing the wallet. A seal, and
 * [canSeal], replace such a key instead: they run when a phrase is in hand (a create or a
 * restore), the dead key can no longer open anything, and a check that only ever asked for
 * the device lock would block a restore on this phone forever.
 *
 * Nothing in this class is logged in a release build. The failures it warns about carry
 * Keystore aliases and crypto stack traces, which is help while building and a description
 * of the install for anyone reading logcat on a shipped phone.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** What reading the stored seed produced. */
    sealed class SeedRead {
        /** The phrase, decrypted. */
        data class Ok(val mnemonic: String) : SeedRead()

        /**
         * The key is bound to the device lock and the user has not authenticated recently
         * enough. Authenticate, then call [readSeed] again.
         */
        object NeedsAuthentication : SeedRead()

        /** The Keystore key was invalidated by a screen-lock change; only a restore can help. */
        object Invalidated : SeedRead()

        /** No wallet is stored on this device. */
        object Absent : SeedRead()

        /** The ciphertext is there but could not be opened for another reason. */
        data class Failed(val detail: String?) : SeedRead()
    }

    /** What sealing a phrase produced. */
    sealed class SeedSeal {
        /** The phrase is sealed and stored. */
        object Sealed : SeedSeal()

        /**
         * The key that would seal it is bound to the device lock and the window has passed.
         * Nothing was written: authenticate and call [saveSeed] again.
         */
        object NeedsAuthentication : SeedSeal()

        /** This phone cannot seal the phrase at all. [detail] says why, in user-facing words. */
        data class Failed(val detail: String?) : SeedSeal()
    }

    /**
     * How well the Keystore protects the key that opens the stored phrase, on this phone.
     *
     * [DEVICE_LOCK] is the strongest state this app can reach: the Keystore itself refuses to
     * use the key until the user has unlocked the phone. [UNBOUND] means the phrase is still
     * encrypted with a key that never leaves the Keystore, but that key can be used without
     * the device lock; the screens' own checks still apply, and the app says so where the
     * phrase is managed instead of leaving the user to assume the stronger state.
     */
    enum class SeedProtection {
        DEVICE_LOCK,
        UNBOUND,
        NONE
    }

    /** [SeedProtection] plus the reason, in words, when the binding could not be applied. */
    data class SeedProtectionStatus(val level: SeedProtection, val detail: String?)

    /**
     * Seals [mnemonic] with a fresh IV.
     *
     * Never writes a plain text copy and never falls back to an unbound key that is already
     * there: the answer is either that the phrase is sealed or that the caller has to
     * authenticate first.
     */
    fun saveSeed(mnemonic: String): SeedSeal {
        val key = try {
            keyForSeal() ?: return SeedSeal.Failed(KEY_FAILURE)
        } catch (refused: KeyBindingRefused) {
            return SeedSeal.Failed(refused.message)
        }

        when (val first = attemptSeal(key, mnemonic)) {
            SealAttempt.Sealed -> Unit
            SealAttempt.NeedsAuthentication -> return SeedSeal.NeedsAuthentication
            is SealAttempt.Failed -> return SeedSeal.Failed(first.detail)
            SealAttempt.Invalidated -> {
                // The key died with the phone's previous lock. The phrase is in hand here, so
                // a fresh key can be made for it - which is what a restore does anyway.
                val replaced = try {
                    replaceInvalidatedKey()
                } catch (refused: KeyBindingRefused) {
                    return SeedSeal.Failed(refused.message)
                } ?: return SeedSeal.Failed(KEY_FAILURE)
                when (val second = attemptSeal(replaced, mnemonic)) {
                    SealAttempt.Sealed -> Unit
                    SealAttempt.NeedsAuthentication -> return SeedSeal.NeedsAuthentication
                    is SealAttempt.Failed -> return SeedSeal.Failed(second.detail)
                    SealAttempt.Invalidated -> return SeedSeal.Failed(KEY_FAILURE)
                }
            }
        }

        prefs.edit()
            .putString(KEY_SEALED_WITH, SeedStorage.CURRENT)
            .apply()
        // The older keys are no longer needed: this build has re-sealed the phrase onto its
        // own key. Keeping them would leave the weaker material able to open an old copy of
        // the ciphertext kept in a phone backup.
        deleteKeys(SeedStorage.OLD_PAIR, SeedStorage.OLD_SINGLE)
        return SeedSeal.Sealed
    }

    /**
     * Whether [saveSeed] would succeed right now, without writing anything.
     *
     * Restoring a wallet replaces the node store before the phrase is sealed, so a seal that
     * turned out to need an authentication at the end would leave the user with neither the
     * old wallet nor the new one. The restore asks this first, while everything is still in
     * place, and only then destroys anything.
     */
    fun canSeal(): SeedSeal {
        val key = try {
            keyForSeal() ?: return SeedSeal.Failed(KEY_FAILURE)
        } catch (refused: KeyBindingRefused) {
            return SeedSeal.Failed(refused.message)
        }
        return when (val first = probe(key)) {
            SealProbe.Ready -> SeedSeal.Sealed
            SealProbe.NeedsAuthentication -> SeedSeal.NeedsAuthentication
            is SealProbe.Failed -> SeedSeal.Failed(first.detail)
            SealProbe.Invalidated -> {
                // A key that died with the phone's previous lock can never open anything
                // again, so replacing it costs nothing that is still readable - and without
                // the replacement a restore would be blocked on this phone for good, since
                // the check would keep answering "authenticate" to a prompt that cannot
                // bring a permanently invalid key back.
                val replaced = try {
                    replaceInvalidatedKey()
                } catch (refused: KeyBindingRefused) {
                    return SeedSeal.Failed(refused.message)
                } ?: return SeedSeal.Failed(KEY_FAILURE)
                when (val second = probe(replaced)) {
                    SealProbe.Ready -> SeedSeal.Sealed
                    SealProbe.NeedsAuthentication -> SeedSeal.NeedsAuthentication
                    is SealProbe.Failed -> SeedSeal.Failed(second.detail)
                    SealProbe.Invalidated -> SeedSeal.Failed(KEY_FAILURE)
                }
            }
        }
    }

    /**
     * Decrypts the seed for this process.
     *
     * @return [SeedRead.Ok] with the phrase, [SeedRead.NeedsAuthentication] when the user has
     * to prove it is them first, [SeedRead.Invalidated] when the key died with the old screen
     * lock, or [SeedRead.Absent]/[SeedRead.Failed].
     */
    fun readSeed(): SeedRead {
        val payload = readPayload() ?: return SeedRead.Absent
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)

        var waited = false
        var invalidated = false
        var lastFailure: String? = null

        // Every generation of the file, newest first. A ciphertext sealed by an older key
        // fails the GCM tag under a newer one (and the other way round), which is what makes
        // this order work without a marker on every payload.
        for (kind in SeedStorage.readOrder) {
            val key = try {
                secretKey(kind)
            } catch (e: KeyPermanentlyInvalidatedException) {
                if (kind == SeedStorage.CURRENT) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "The wallet key was invalidated by a screen-lock change", e)
                    return SeedRead.Invalidated
                }
                null
            } ?: continue

            try {
                val mnemonic = decrypt(key, iv, ciphertext)
                if (SeedStorage.needsReseal(kind) && !resealOntoCurrent(kind, mnemonic)) {
                    // It opened, so the wallet works; it just stays on the older key until an
                    // authentication lets the current one take over.
                    prefs.edit().putString(KEY_SEALED_WITH, kind).apply()
                }
                return SeedRead.Ok(mnemonic)
            } catch (e: UserNotAuthenticatedException) {
                // Expected for a bound key whose window has passed. Remembered rather than
                // returned at once: a newer key exists for the seal but this payload may be
                // an older one that this key cannot open anyway.
                waited = true
            } catch (e: KeyPermanentlyInvalidatedException) {
                if (kind == SeedStorage.CURRENT) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "The wallet key was invalidated by a screen-lock change", e)
                    return SeedRead.Invalidated
                }
                // An older generation's key died with the phone's previous lock. The payload
                // may well be that key's, so this is not "no key here" - it is the one case
                // where nothing on this device can open the phrase any more.
                invalidated = true
            } catch (e: Exception) {
                // Wrong key for this ciphertext (a GCM tag mismatch) or a damaged payload.
                lastFailure = e.message
            }
        }

        return when {
            waited -> SeedRead.NeedsAuthentication
            invalidated -> {
                // Said plainly, and pointed at the recovery phrase: a vague failure would send
                // the user looking for a bug instead of for their words.
                if (BuildConfig.DEBUG) Log.w(TAG, "An older wallet key was invalidated by a screen-lock change")
                SeedRead.Invalidated
            }
            else -> {
                if (BuildConfig.DEBUG) Log.e(TAG, "No stored key opened the seed")
                SeedRead.Failed(lastFailure ?: "The wallet key is missing")
            }
        }
    }

    fun hasSeed(): Boolean = prefs.contains(KEY_SEED)

    /**
     * Reads what actually protects the stored phrase, from the Keystore entry itself rather
     * than from a flag written when it was created: a wallet sealed by an older build, by a
     * key that could not be bound, or before this phone had a screen lock is reported as it is.
     */
    fun protectionStatus(): SeedProtectionStatus {
        if (!hasSeed()) return SeedProtectionStatus(SeedProtection.NONE, null)
        val kind = SeedStorage.kindOfMarker(prefs.getString(KEY_SEALED_WITH, null))
            ?: when {
                hasKey(KEY_ALIAS) -> SeedStorage.CURRENT
                hasKey(DEC_ALIAS) -> SeedStorage.OLD_PAIR
                hasKey(LEGACY_ALIAS) -> SeedStorage.OLD_SINGLE
                else -> null
            }
        val alias = aliasOf(kind)
        val bound = runCatching {
            alias?.let { key(it) }?.let { entry -> keyInfo(entry).isUserAuthenticationRequired }
        }.getOrNull()
        val level = SeedStorage.protectionOf(kind, bound)
        return SeedProtectionStatus(
            level = level,
            detail = if (level == SeedProtection.DEVICE_LOCK) {
                null
            } else {
                SeedStorage.unboundDetail(prefs.getString(KEY_UNBOUND_REASON, null), isDeviceSecure())
            }
        )
    }

    private fun keyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo

    /**
     * Forgets the seed and the keys that sealed it.
     *
     * Removing the ciphertext alone would leave the AES keys in the Android Keystore, where
     * they outlive a wipe and could decrypt any copy of the ciphertext kept elsewhere (an
     * old backup of the preferences, for instance). The keys are deleted with the seed.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_SEED)
            .remove(KEY_UNBOUND_REASON)
            .remove(KEY_SEALED_WITH)
            .apply()
        deleteKeys(SeedStorage.CURRENT, SeedStorage.OLD_PAIR, SeedStorage.OLD_SINGLE)
    }

    // ------------------------------------------------------------------- internals

    /** One attempt to seal with [key]. */
    private sealed class SealAttempt {
        object Sealed : SealAttempt()
        object NeedsAuthentication : SealAttempt()
        object Invalidated : SealAttempt()
        data class Failed(val detail: String?) : SealAttempt()
    }

    /** One attempt to use [key] and throw the result away. */
    private sealed class SealProbe {
        object Ready : SealProbe()
        object NeedsAuthentication : SealProbe()
        object Invalidated : SealProbe()
        data class Failed(val detail: String?) : SealProbe()
    }

    /** Encrypts and discards: the question is whether the Keystore will use [key] at all. */
    private fun probe(key: SecretKey): SealProbe = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(PROBE.toByteArray(Charsets.UTF_8))
        SealProbe.Ready
    } catch (e: UserNotAuthenticatedException) {
        SealProbe.NeedsAuthentication
    } catch (e: KeyPermanentlyInvalidatedException) {
        SealProbe.Invalidated
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "The seal check failed", e)
        SealProbe.Failed(e.message)
    }

    private fun attemptSeal(key: SecretKey, mnemonic: String): SealAttempt = try {
        sealWith(key, mnemonic)
        SealAttempt.Sealed
    } catch (e: UserNotAuthenticatedException) {
        SealAttempt.NeedsAuthentication
    } catch (e: KeyPermanentlyInvalidatedException) {
        SealAttempt.Invalidated
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "Could not seal the phrase", e)
        SealAttempt.Failed(e.message)
    }

    /**
     * The current key, created if this phone has none yet; null when the Keystore refuses.
     *
     * A key that died with the phone's previous screen lock is replaced here rather than
     * reported, because every caller of this is holding (or checking) a phrase to seal: the
     * old key cannot open anything any more, and a new one is what lets the wallet exist on
     * this phone at all.
     */
    private fun keyForSeal(): SecretKey? = try {
        currentKey(create = true)
    } catch (e: KeyPermanentlyInvalidatedException) {
        replaceInvalidatedKey()
    } catch (refused: KeyBindingRefused) {
        // Carried out to the caller, which says why in words instead of "no key".
        throw refused
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "No key to seal the phrase with", e)
        null
    }

    /** Throws away a key the phone's old lock killed and makes a new one; null on failure. */
    private fun replaceInvalidatedKey(): SecretKey? {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "The wallet key died with the phone's previous lock; making a new one")
        }
        return try {
            regenerate()
        } catch (refused: KeyBindingRefused) {
            throw refused
        } catch (t: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Could not replace the invalidated key", t)
            null
        }
    }

    private fun readPayload(): ByteArray? {
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return if (payload.size <= IV_BYTES) null else payload
    }

    /** Seals [mnemonic] with [key] and writes the ciphertext, or throws before writing anything. */
    private fun sealWith(key: SecretKey, mnemonic: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        prefs.edit()
            .putString(KEY_SEED, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun hasKey(alias: String): Boolean =
        runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    private fun aliasOf(kind: String?): String? = when (kind) {
        SeedStorage.CURRENT -> KEY_ALIAS
        SeedStorage.OLD_PAIR -> DEC_ALIAS
        SeedStorage.OLD_SINGLE -> LEGACY_ALIAS
        else -> null
    }

    /**
     * The Keystore entry for the key [kind] names, or null when there is none this build can
     * read.
     *
     * An invalidated key is rethrown rather than folded into null: the difference between
     * "there is no key" and "the phone's lock changed" is the difference between a broken
     * install and a wallet that needs its recovery phrase.
     */
    private fun secretKey(kind: String): SecretKey? {
        val alias = aliasOf(kind) ?: return null
        return key(alias)
    }

    private fun key(alias: String): SecretKey? = try {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw e
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "The Keystore entry $alias could not be read", e)
        null
    }

    /**
     * Seals [mnemonic] with the current key after an older one opened it.
     *
     * False when that needs an authentication the caller has not given: the old ciphertext
     * stays exactly where it is, and the next read tries again.
     */
    private fun resealOntoCurrent(kind: String, mnemonic: String): Boolean {
        val seal = saveSeed(mnemonic)
        if (seal is SeedSeal.Sealed) {
            prefs.edit().putString(KEY_SEALED_WITH, SeedStorage.CURRENT).apply()
            deleteKeys(kind)
            return true
        }
        return false
    }

    /** The current key, created the first time it is needed. */
    private fun currentKey(create: Boolean): SecretKey? {
        keyStore().let { store ->
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        }
        if (!create) return null
        return generate()
    }

    /** Throws away the current key and makes a new one; used when the old one is invalidated. */
    private fun regenerate(): SecretKey {
        deleteKeys(SeedStorage.CURRENT)
        return generate()
    }

    /**
     * Generates the wallet key inside the Android Keystore.
     *
     * `KeyGenerator.getInstance(AES, "AndroidKeyStore")` hands the material to the Keystore
     * as it is made: it is never a `ByteArray` this process can hold, unlike the imported key
     * pair this build replaces.
     *
     * The key is bound to the device lock where the phone has one. A phone with no screen lock
     * has nothing to bind to, so its key is generated unbound and reported as
     * [SeedProtection.UNBOUND] with the reason - a state the user is told about.
     *
     * A Keystore that *refuses* the bound key on a phone that does have a lock is different:
     * sealing with an unbound key there would be a silent downgrade of exactly the protection
     * this class exists to provide, under a lock the user believes is required. That is
     * fail-closed - nothing is generated, [KeyBindingRefused] is thrown, and the caller says
     * the phrase was not stored rather than storing it more weakly.
     */
    private fun generate(): SecretKey {
        val store = keyStore()
        deleteKeys(SeedStorage.CURRENT)
        prefs.edit().remove(KEY_UNBOUND_REASON).apply()

        if (!isDeviceSecure()) {
            prefs.edit().putString(KEY_UNBOUND_REASON, SeedStorage.REASON_NO_SCREEN_LOCK).apply()
            return generateKey(authBound = false)
        }

        return try {
            generateKey(authBound = true)
        } catch (t: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "This Keystore refused a key bound to the screen lock", t)
            }
            // No key is left behind, and nothing weaker is made in its place.
            runCatching { store.deleteEntry(KEY_ALIAS) }
            throw KeyBindingRefused(t)
        }
    }

    private fun generateKey(authBound: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            // A fresh IV per seal is what makes GCM safe, and the platform enforces it for
            // the keys it generates.
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (authBound) {
                    setUserAuthenticationRequired(true)
                    // Enrolling another fingerprint must not kill the wallet key: the phone
                    // still has its screen lock, and a key that dies on a new finger would
                    // cost the user a restore of their phrase for an unrelated settings change.
                    setInvalidatedByBiometricEnrollment(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            AUTH_WINDOW_SECONDS,
                            KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
                    }
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun deleteKeys(vararg kinds: String) {
        val aliases = kinds.mapNotNull { aliasOf(it) }.toMutableList()
        val keyStore = runCatching { keyStore() }.getOrNull() ?: return
        // The pair's encrypt half is never a way in, but it is still key material that has no
        // business surviving a wipe or a re-seal.
        if (SeedStorage.OLD_PAIR in kinds) aliases += ENC_ALIAS
        aliases.forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
    }

    private fun isDeviceSecure(): Boolean {
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }

    companion object {
        private const val TAG = "PureWalletSecureStore"
        private const val NAME = "purewallet_secrets"
        private const val KEY_SEED = "seed_ciphertext"

        /** Why the wallet key is not bound to the screen lock, when it is not. */
        private const val KEY_UNBOUND_REASON = "seed_key_unbound_reason"

        /** Which key sealed the stored ciphertext; see [SeedStorage]. */
        private const val KEY_SEALED_WITH = "seed_sealed_with"

        private const val KEYSTORE = "AndroidKeyStore"

        /**
         * The key this build seals and opens with, generated inside the Keystore.
         *
         * The name still carries the app's old name because it addresses what earlier builds
         * already wrote, not because anything a user sees is called that. A Keystore alias is
         * the address of a key, not a label: renaming it would leave every sealed ciphertext in
         * this app's storage pointing at an entry that no longer exists, and the only way back
         * to those funds would be the recovery phrase. The package name is a separate matter and
         * is the publisher's own (`com.corejbh.zerowallet`); these internal names stay put.
         */
        private const val KEY_ALIAS = "purewallet_seed_aes"

        /** The single unbound key of builds up to 1.1.10; read once during an upgrade. */
        private const val LEGACY_ALIAS = "purewallet_seed_key"

        /** The imported encrypt/decrypt pair of 1.1.11-1.1.16; read once during an upgrade. */
        private const val ENC_ALIAS = "purewallet_seed_key_enc"
        private const val DEC_ALIAS = "purewallet_seed_key_dec"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256

        /** What [canSeal] encrypts and throws away; never stored. */
        private const val PROBE = "zerowallet-seal-probe"

        /** Said whenever the Keystore will not give this app a key at all. */
        private const val KEY_FAILURE = "This phone's Keystore would not create the wallet key"

        /** Said when it will create one, but not one the phone's screen lock protects. */
        internal const val KEY_BINDING_REFUSED =
            "This phone's Keystore refused to create a wallet key tied to the screen lock, so " +
                "nothing was stored. A wallet key that opened without your lock would be a " +
                "weaker copy of what this app promises, so it is not used as a stand-in."

        /**
         * How long the key stays usable after the user proves it is them. Short enough that
         * an app process picked up later cannot read the seed on its own, long enough that
         * opening the wallet right after unlocking the phone asks nothing.
         */
        private const val AUTH_WINDOW_SECONDS = 60
    }
}

/**
 * Thrown by a wallet call that had to seal the recovery phrase and could not, because the
 * Keystore key that seals it is bound to the device lock and the window has passed.
 *
 * The screen that caught it asks for the lock and calls the same operation again; nothing was
 * written the first time, so a retry is the whole of the recovery. It is a separate type so a
 * caller cannot confuse it with a Keystore that refuses to work at all, which no
 * authentication can fix.
 */
class SeedSealRequired : Exception(
    "Authenticate to let this phone's Keystore seal the recovery phrase"
)

/**
 * The Keystore will make a key, but not one the phone's screen lock protects.
 *
 * Thrown instead of generating an unbound key for a phone that has a lock: sealing there would
 * quietly deliver less protection than the app says it does. The message is written for the
 * user, because it is what the setup screen shows when nothing could be stored.
 */
class KeyBindingRefused(cause: Throwable?) : Exception(
    SecureStore.KEY_BINDING_REFUSED,
    cause
)
