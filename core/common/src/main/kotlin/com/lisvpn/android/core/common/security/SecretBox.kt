package com.lisvpn.android.core.common.security

/**
 * Envelope encryption primitive used to protect sensitive strings persisted on the device
 * (e.g. VPN credentials, subscription tokens, outbound JSON, raw VLESS URIs).
 *
 * Guarantees:
 *  - Ciphertext is deterministically prefixed with a scheme identifier so the caller can
 *    distinguish legacy plaintext values from encrypted ones (see [isEncrypted]).
 *  - Implementations MUST ensure each encryption produces a fresh random nonce — do NOT rely
 *    on [encrypt] output being stable for a given plaintext.
 *  - The key lives in Android Keystore (hardware-backed when available); wiping app data
 *    destroys the key.
 *
 * The interface is small on purpose so it can be faked in unit tests without pulling in Tink.
 */
interface SecretBox {

    /** Returns a base64-encoded ciphertext with a scheme prefix (`v1:`). */
    fun encrypt(plaintext: String): String

    /**
     * Decrypts [ciphertext] produced by [encrypt]. Throws if the prefix is unknown or the
     * ciphertext is tampered with.
     */
    fun decrypt(ciphertext: String): String

    /**
     * Backwards-compatible decrypt used while migrating existing Room rows.
     *
     * If [value] carries a known scheme prefix → decrypt it.
     * If decryption fails or the prefix is missing → return [value] verbatim (legacy plaintext).
     *
     * This lets callers read old rows without migration; the next write-path re-encrypts them.
     */
    fun tryDecrypt(value: String): String
}

/** Scheme prefix reserved for the current AES-256-GCM Tink envelope. */
internal const val SECRET_SCHEME_V1 = "v1:"

/** @return true if [value] was produced by [SecretBox.encrypt] (v1+ scheme). */
fun String.isEncryptedSecret(): Boolean = startsWith(SECRET_SCHEME_V1)
