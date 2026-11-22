package com.jbitmstr.purewallet.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.jbitmstr.purewallet.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the wallet's own small records (the balance cache, payment notes) with AES-256-GCM
 * and a Keystore key, so amounts and merchant descriptions are not sitting in plain text in
 * the app's preferences.
 *
 * The key lives in the Android Keystore and never leaves it. It is deliberately not bound to
 * the device lock: these records are needed the moment the wallet opens (the cached numbers
 * are what the screen shows while the node syncs), they are re-creatable by syncing again,
 * and asking for an authentication to read a stale balance would be worse for the user than
 * the small exposure it removes. Losing them (a key lost to a reset, a corrupted value) costs
 * a sync, never funds: every caller treats a missing or unreadable value as "nothing cached
 * yet".
 *
 * Values carry a [PREFIX] marker, and a value without it is refused rather than handed back.
 * An earlier build read unsealed values back for one upgrade, so a phone that skipped the
 * migration could still show them; 1.2.0 removes that window entirely. A plain-text record can
 * only be one this build did not write, and returning it would fill the screen with whatever a
 * tampered or corrupted preference file happens to hold.
 *
 * [seal] never falls back to the value it was given: a Keystore that refuses to seal returns
 * null, and the caller writes nothing (see `Prefs.saveCachedWallet` and
 * `PaymentNotes.put`). Writing the plain text instead would put exactly the amounts and
 * merchant names this class exists to hide into the preferences file.
 *
 * Nothing here is logged in a release build: a failure names a Keystore alias and carries a
 * stack trace from the crypto layer, which is a build-time debugging aid, not something a
 * shipped wallet should leave in logcat for any process that can read it.
 */
object SecretStore {

    private const val TAG = "PureWalletSecretStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "purewallet_data_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** Returns `v1:<base64 iv+ciphertext>`, or null when the Keystore cannot seal it. */
    fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w(TAG, "Unable to seal a stored value", it)
    }.getOrNull()

    /**
     * Opens a value written by [seal].
     *
     * A value without the marker is refused, not returned. Up to 1.1.18 there was a window in
     * which unsealed text was handed back so an upgrade could re-seal it; every path that
     * could reach that window is gone, because a value this app did not seal is not something
     * to put on the wallet's screen or in its history.
     */
    fun open(stored: String): String? {
        if (!isSealed(stored)) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "A stored value has no seal marker and was refused")
            }
            return null
        }
        val payload = runCatching {
            Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        if (payload.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES))
            )
            String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to open a stored value", it)
        }.getOrNull()
    }

    /** True when [value] was written by [seal] rather than by a build before it. */
    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /** Deletes the data key, for a wipe: the ciphertext goes with the preferences. */
    fun deleteKey() {
        runCatching {
            keyStore().deleteEntry(KEY_ALIAS)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to delete the data key", it)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
