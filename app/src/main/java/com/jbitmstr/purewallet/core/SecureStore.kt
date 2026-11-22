package com.jbitmstr.purewallet.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the seed phrase with an AES-256-GCM key that is generated inside the
 * Android Keystore and never leaves it. Only the ciphertext is written to disk.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveSeed(mnemonic: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        prefs.edit()
            .putString(KEY_SEED, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun loadSeed(): String? {
        val encoded = prefs.getString(KEY_SEED, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to decrypt the stored seed", e)
            null
        }
    }

    fun hasSeed(): Boolean = prefs.contains(KEY_SEED)

    /**
     * Forgets the seed and the key that sealed it.
     *
     * Removing the ciphertext alone would leave the AES key in the Android Keystore, where
     * it outlives a wipe and could decrypt any copy of the ciphertext kept elsewhere (an
     * old backup of the preferences, for instance). The key is deleted with the seed.
     */
    fun clear() {
        prefs.edit().remove(KEY_SEED).apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "PureWalletSecureStore"
        private const val NAME = "purewallet_secrets"
        private const val KEY_SEED = "seed_ciphertext"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "purewallet_seed_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
