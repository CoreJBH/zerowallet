package com.jbitmstr.purewallet.core

/**
 * Which key sealed the stored recovery phrase, and what that means for its protection.
 *
 * Three generations of the file exist in the field, and a wallet must survive all of them:
 *
 * - [CURRENT] - one AES-256 key generated *inside* the Android Keystore
 *   (`KeyGenerator.getInstance(AES, "AndroidKeyStore")`), bound to the screen lock with a
 *   short validity window where the phone allows it. This is what this build writes.
 * - [OLD_PAIR] - the keys of 1.1.11 to 1.1.16. The material was generated in software and
 *   imported twice into the Keystore, once allowed only to encrypt and once, bound to the
 *   lock, only to decrypt. It could open a phrase without a prompt and read it back only
 *   after one, but the key existed outside the Keystore for a moment, which is what the
 *   import-free design removes.
 * - [OLD_SINGLE] - the single unbound key of 1.1.10 and earlier.
 *
 * Nothing here touches Android, so the read order, the marker and the wording of the
 * unbound explanation are unit-tested instead of being re-derived at every call site.
 */
internal object SeedStorage {

    /** The one key this build seals with and opens with. */
    const val CURRENT = "current"

    /** The encrypt/decrypt pair of 1.1.11-1.1.16; opened with its decrypt half. */
    const val OLD_PAIR = "old_pair"

    /** The single unbound key of 1.1.10 and earlier. */
    const val OLD_SINGLE = "old_single"

    /**
     * The order stored ciphertexts are tried in.
     *
     * The current key first, because every save since this build has used it. An older key
     * is tried only when the ciphertext's tag does not verify under a newer one, which is
     * how a wallet sealed by an earlier build is opened once and then re-sealed onto the
     * current key. The encrypt-only half of [OLD_PAIR] is deliberately absent: it can never
     * open anything, and trying it would only add a failed decrypt to every start.
     */
    val readOrder: List<String> = listOf(CURRENT, OLD_PAIR, OLD_SINGLE)

    /** Every key that may still open a stored phrase, for [SecureStore.clear]. */
    val allKinds: List<String> = listOf(CURRENT, OLD_PAIR, OLD_SINGLE)

    /** The value kept in preferences for [kind], or null for something this build cannot name. */
    fun marker(kind: String): String? = when (kind) {
        CURRENT, OLD_PAIR, OLD_SINGLE -> kind
        else -> null
    }

    /** The kind a stored marker names; an unknown or missing marker says nothing. */
    fun kindOfMarker(marker: String?): String? = marker?.takeIf { it in allKinds }

    /** True when the ciphertext sealed by [kind] has to be re-sealed onto [CURRENT]. */
    fun needsReseal(kind: String): Boolean = kind != CURRENT

    /**
     * Which protection [kind] gives, given what the Keystore reports about that key.
     *
     * The answer comes from the key itself rather than from a flag written when it was
     * created: a wallet sealed before this phone had a screen lock, or by a build whose key
     * the Keystore would not bind, is reported as it actually is. A key the Keystore cannot
     * read at all is [SecureStore.SeedProtection.UNBOUND] rather than a claim of strength.
     */
    fun protectionOf(kind: String?, authRequired: Boolean?): SecureStore.SeedProtection = when {
        kind == null -> SecureStore.SeedProtection.UNBOUND
        authRequired == true -> SecureStore.SeedProtection.DEVICE_LOCK
        else -> SecureStore.SeedProtection.UNBOUND
    }

    /** Why the key is not bound, in the words the user is shown. */
    fun unboundDetail(reason: String?, deviceSecureNow: Boolean): String = when (reason) {
        REASON_GENERATE_REFUSED, LEGACY_REASON_IMPORT_REFUSED ->
            "This phone's Keystore would not keep the wallet key bound to the screen lock, so " +
                "the key opens without one. Sending, showing the phrase and wiping still ask " +
                "for your fingerprint or screen lock first."
        REASON_NO_SCREEN_LOCK -> if (deviceSecureNow) {
            "This wallet was sealed before this phone had a screen lock, so its key is not " +
                "bound to one. Restoring the wallet with your recovery phrase on this phone " +
                "seals it again, now bound to the screen lock."
        } else {
            "This phone has no screen lock, so the wallet key cannot be bound to one. Set a " +
                "PIN, pattern or password in Android settings; restoring the wallet afterwards " +
                "seals it again, bound to the lock."
        }
        REASON_AUTH_UNAVAILABLE ->
            "The wallet key is held in the Android Keystore but could not be bound to this " +
                "phone's screen lock. Sending, showing the phrase and wiping still ask for the " +
                "lock first."
        else ->
            "The wallet key is held in the Android Keystore but is not bound to the screen " +
                "lock. Sending, showing the phrase and wiping still ask for it first."
    }

    const val REASON_NO_SCREEN_LOCK = "no_screen_lock"

    /** A Keystore that refused to create the bound key. */
    const val REASON_GENERATE_REFUSED = "generate_refused"

    /** A device that is secure but could not authenticate (no biometric, credential locked out). */
    const val REASON_AUTH_UNAVAILABLE = "auth_unavailable"

    /** What 1.1.11-1.1.16 stored when the imported bound key was refused. Still read. */
    const val LEGACY_REASON_IMPORT_REFUSED = "import_refused"
}
