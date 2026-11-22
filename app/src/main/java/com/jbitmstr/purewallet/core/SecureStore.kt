package com.jbitmstr.purewallet.core

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the seed phrase with AES-256-GCM keys that are generated inside the Android
 * Keystore and never leave it. Only the ciphertext is written to disk.
 *
 * Two aliases hold the same AES key material, each allowed only one direction:
 *
 * - [ENC_ALIAS] may encrypt and nothing else. It is not bound to the user, so the wallet can
 *   be sealed with no prompt at all (creating a wallet, restoring one, upgrading an older
 *   install). Holding this alias is useless for reading the wallet back.
 * - [DEC_ALIAS] may decrypt and nothing else, and on Android 8 and later on a phone with a
 *   screen lock it is created with `setUserAuthenticationRequired(true)`. Reading the seed
 *   then needs the user to have authenticated on the device (screen unlock, or the prompt
 *   the wallet shows): knowing the app's code, or getting a shell into it, is not enough to
 *   open the phrase. The Keystore enforces that itself, so no screen can be bypassed.
 *
 * The binding uses a short validity window instead of a per-operation one, on purpose: a
 * per-operation key needs a `CryptoObject` prompt around every single use, and the node has
 * to read the seed on its own when the app comes back to the screen. With a window, the
 * seed opens right after the user unlocks the phone and asks for the prompt only when that
 * window has passed.
 *
 * Lock changes: the Keystore invalidates an auth-bound key when the secure lock screen is
 * removed or reset (a normal PIN change keeps it usable). [readSeed] reports that as
 * [SeedRead.Invalidated] instead of a vague failure, so the app can say what happened and
 * send the user to the recovery phrase rather than silently losing the wallet.
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

    /**
     * How well the Keystore protects the key that opens the stored phrase, on this phone.
     *
     * [DEVICE_LOCK] is the strongest state this app can reach: the Keystore itself refuses to
     * use the decrypt key until the user has unlocked the phone. [UNBOUND] means the phrase is
     * still encrypted with a key that never leaves the Keystore, but that key can be used
     * without the device lock; the screens' own checks still apply, and the app says so where
     * the phrase is managed instead of leaving the user to assume the stronger state.
     */
    enum class SeedProtection {
        DEVICE_LOCK,
        UNBOUND,
        NONE
    }

    /** [SeedProtection] plus the reason, in words, when the binding could not be applied. */
    data class SeedProtectionStatus(val level: SeedProtection, val detail: String?)

    /** Seals [mnemonic] with a fresh IV. Never asks the user anything. */
    fun saveSeed(mnemonic: String) {
        ensureKeyPair()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptKey())
        val ciphertext = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        prefs.edit()
            .putString(KEY_SEED, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
        // The upgrade path is done: the old single key can no longer open anything, and
        // keeping it would leave a copy of the key material able to read old backups.
        runCatching { keyStore().deleteEntry(LEGACY_ALIAS) }
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

        // An install from before the split keys: read it with the old key and move it onto
        // the new pair right away, so the next read is the protected one.
        val legacy = try {
            key(LEGACY_ALIAS)
        } catch (e: KeyPermanentlyInvalidatedException) {
            null
        }
        if (legacy != null) {
            return try {
                val mnemonic = decrypt(legacy, iv, ciphertext)
                runCatching { saveSeed(mnemonic) }
                    .onFailure { Log.w(TAG, "Seed opened with the old key but not re-sealed", it) }
                SeedRead.Ok(mnemonic)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to decrypt the stored seed with the old key", e)
                SeedRead.Failed(e.message)
            }
        }

        val decryptKey = try {
            key(DEC_ALIAS) ?: key(ENC_ALIAS)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "The wallet key was invalidated by a screen-lock change", e)
            markKeysInvalidated()
            return SeedRead.Invalidated
        } ?: return SeedRead.Failed("The wallet key is missing")

        return try {
            SeedRead.Ok(decrypt(decryptKey, iv, ciphertext))
        } catch (e: UserNotAuthenticatedException) {
            // Expected: the key is bound to the device lock and the window has passed.
            SeedRead.NeedsAuthentication
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "The wallet key was invalidated by a screen-lock change", e)
            markKeysInvalidated()
            SeedRead.Invalidated
        } catch (e: Exception) {
            Log.e(TAG, "Unable to decrypt the stored seed", e)
            SeedRead.Failed(e.message)
        }
    }

    fun hasSeed(): Boolean = prefs.contains(KEY_SEED)

    /**
     * Reads what actually protects the stored phrase, from the Keystore entry itself rather
     * than from a flag written when it was created: a wallet sealed by an older build, by the
     * single-key fallback, or before this phone had a screen lock is reported as it is.
     */
    fun protectionStatus(): SeedProtectionStatus {
        if (!hasSeed()) return SeedProtectionStatus(SeedProtection.NONE, null)
        val bound = runCatching {
            key(DEC_ALIAS)?.let { keyInfo(it).isUserAuthenticationRequired }
        }.getOrNull()
        return if (bound == true) {
            SeedProtectionStatus(SeedProtection.DEVICE_LOCK, null)
        } else {
            SeedProtectionStatus(SeedProtection.UNBOUND, unboundDetail())
        }
    }

    private fun keyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo

    /**
     * Why the key is not bound, in the words the user is shown. A phone that has gained a
     * screen lock since the wallet was sealed cannot be bound retroactively - the Keystore key
     * exists already - so that case says what would fix it instead of pretending it is fine.
     */
    private fun unboundDetail(): String = when (prefs.getString(KEY_UNBOUND_REASON, null)) {
        REASON_IMPORT_REFUSED ->
            "This phone's Keystore would not keep the wallet key bound to the screen lock, so " +
                "the key opens without one. Sending, showing the phrase and wiping still ask " +
                "for your fingerprint or screen lock first."
        REASON_NO_SCREEN_LOCK -> if (isDeviceSecure()) {
            "This wallet was sealed before this phone had a screen lock, so its key is not " +
                "bound to one. Restoring the wallet with your recovery phrase on this phone " +
                "seals it again, now bound to the screen lock."
        } else {
            "This phone has no screen lock, so the wallet key cannot be bound to one. Set a " +
                "PIN, pattern or password in Android settings; restoring the wallet afterwards " +
                "seals it again, bound to the lock."
        }
        else ->
            "The wallet key is held in the Android Keystore but is not bound to the screen " +
                "lock. Sending, showing the phrase and wiping still ask for it first."
    }

    /**
     * Forgets the seed and the keys that sealed it.
     *
     * Removing the ciphertext alone would leave the AES keys in the Android Keystore, where
     * they outlive a wipe and could decrypt any copy of the ciphertext kept elsewhere (an
     * old backup of the preferences, for instance). The keys are deleted with the seed.
     */
    fun clear() {
        prefs.edit().remove(KEY_SEED).remove(KEY_KEYS_INVALIDATED).remove(KEY_UNBOUND_REASON).apply()
        val keyStore = runCatching { keyStore() }.getOrNull() ?: return
        listOf(LEGACY_ALIAS, ENC_ALIAS, DEC_ALIAS).forEach { alias ->
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    // ------------------------------------------------------------------- internals

    private fun readPayload(): ByteArray? {
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return if (payload.size <= IV_BYTES) null else payload
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * The Keystore entry for [alias], or null when there is none this build can read.
     *
     * An invalidated key is rethrown rather than folded into null: the difference between
     * "there is no key" and "the phone's lock changed" is the difference between a broken
     * install and a wallet that needs its recovery phrase.
     */
    private fun key(alias: String): SecretKey? = try {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "The Keystore entry $alias could not be read", e)
        null
    }

    private fun markKeysInvalidated() {
        prefs.edit().putBoolean(KEY_KEYS_INVALIDATED, true).apply()
    }

    /**
     * Makes sure the split keys exist.
     *
     * A pair that the Keystore invalidated (its decrypt half) cannot be repaired: the
     * material cannot be read back out, so a fresh pair is generated and the caller's new
     * ciphertext is sealed with it. That only happens on the restore path, where the
     * phrase is known again.
     *
     * If the device refuses the imported pair at all, a single non-auth key is generated
     * instead. That is the protection level the wallet had before 1.1.11, and it keeps a
     * wallet working on a device whose Keystore cannot do better; the screen-based checks
     * still apply. It is not silent any more: the reason is stored and [protectionStatus]
     * reports the wallet as unbound, which is what the Settings sheet shows.
     */
    private fun ensureKeyPair() {
        val keyStore = keyStore()
        val invalidated = prefs.getBoolean(KEY_KEYS_INVALIDATED, false)
        if (!invalidated && keyStore.containsAlias(DEC_ALIAS)) return

        listOf(LEGACY_ALIAS, ENC_ALIAS, DEC_ALIAS).forEach { alias ->
            runCatching { keyStore.deleteEntry(alias) }
        }
        // What the previous pair could not do says nothing about this one.
        prefs.edit().remove(KEY_UNBOUND_REASON).apply()

        val imported = runCatching {
            // The material is generated in software once so it can be imported under both
            // aliases with different protections; from then on it lives only in the Keystore.
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
            generator.init(KEY_BITS)
            val material = generator.generateKey()

            keyStore.setEntry(
                ENC_ALIAS,
                KeyStore.SecretKeyEntry(material),
                KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )

            val decryptProtection = KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            bindToDeviceLock(decryptProtection)
            keyStore.setEntry(DEC_ALIAS, KeyStore.SecretKeyEntry(material), decryptProtection.build())
        }

        if (imported.isFailure) {
            Log.w(TAG, "This device refused the split keys; using one non-auth key", imported.exceptionOrNull())
            // Not silent: the status the Settings sheet reads says this wallet's key is not
            // bound to the screen lock, and why.
            prefs.edit().putString(KEY_UNBOUND_REASON, REASON_IMPORT_REFUSED).apply()
            runCatching { keyStore.deleteEntry(ENC_ALIAS) }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                DEC_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
            generator.init(spec)
            generator.generateKey()
        }

        prefs.edit().remove(KEY_KEYS_INVALIDATED).apply()
    }

    /** The key that seals a new phrase: the encrypt-only half, or the single fallback key. */
    private fun encryptKey(): SecretKey =
        key(ENC_ALIAS) ?: key(DEC_ALIAS) ?: throw IllegalStateException("no key to seal the seed with")

    /**
     * Puts the device-lock requirement on the decrypt key, on every API this app supports.
     *
     * Android 11 (API 30) added [KeyProtection.Builder.setUserAuthenticationParameters], which
     * takes the allowed authenticators as well as the window; before it the same window is set
     * with [KeyProtection.Builder.setUserAuthenticationValidityDurationSeconds]. Returning
     * early below API 30, as an earlier build did, left every Android 8-10 phone with a key
     * that never asked for the device lock at all.
     *
     * If the phone has no screen lock there is nothing to bind to; that is recorded so the
     * status can say so, and the window is left unset.
     */
    private fun bindToDeviceLock(builder: KeyProtection.Builder) {
        // A phone without a screen lock has nothing to bind to; the reason is recorded so the
        // status can say so, and the window is left unset.
        if (!isDeviceSecure()) {
            prefs.edit().putString(KEY_UNBOUND_REASON, REASON_NO_SCREEN_LOCK).apply()
            return
        }
        builder.setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_WINDOW_SECONDS,
                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }
    }

    private fun isDeviceSecure(): Boolean {
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }

    companion object {
        private const val TAG = "PureWalletSecureStore"
        private const val NAME = "purewallet_secrets"
        private const val KEY_SEED = "seed_ciphertext"
        private const val KEY_KEYS_INVALIDATED = "seed_keys_invalidated"

        /** Why the wallet key is not bound to the screen lock, when it is not. */
        private const val KEY_UNBOUND_REASON = "seed_key_unbound_reason"
        private const val REASON_NO_SCREEN_LOCK = "no_screen_lock"
        private const val REASON_IMPORT_REFUSED = "import_refused"
        private const val KEYSTORE = "AndroidKeyStore"

        /** The single key of builds up to 1.1.10; read once during an upgrade. */
        private const val LEGACY_ALIAS = "purewallet_seed_key"

        private const val ENC_ALIAS = "purewallet_seed_key_enc"
        private const val DEC_ALIAS = "purewallet_seed_key_dec"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256

        /**
         * How long the decrypt key stays usable after the user proves it is them. Short
         * enough that an app process picked up later cannot read the seed on its own, long
         * enough that opening the wallet right after unlocking the phone asks nothing.
         */
        private const val AUTH_WINDOW_SECONDS = 60
    }
}
