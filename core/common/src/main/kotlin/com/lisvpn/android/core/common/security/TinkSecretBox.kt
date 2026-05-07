package com.lisvpn.android.core.common.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Production [SecretBox] backed by Google Tink AES-256-GCM with the master key stored in
 * Android Keystore. The keyset material itself lives in a dedicated SharedPreferences file,
 * wrapped by the Keystore master key (envelope encryption).
 *
 * Initialisation is lazy and happens on the first [encrypt] / [decrypt] call on a background
 * thread; keep calls off the main thread when the app has just started.
 */
@Singleton
class TinkSecretBox @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecretBox {

    @Volatile
    private var cachedAead: Aead? = null
    private val initLock = Any()

    override fun encrypt(plaintext: String): String {
        val aead = aead()
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), /* associatedData = */ null)
        return SECRET_SCHEME_V1 + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        require(ciphertext.startsWith(SECRET_SCHEME_V1)) { "unsupported secret scheme" }
        val encoded = ciphertext.removePrefix(SECRET_SCHEME_V1)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val plain = aead().decrypt(raw, /* associatedData = */ null)
        return String(plain, Charsets.UTF_8)
    }

    override fun tryDecrypt(value: String): String {
        if (!value.startsWith(SECRET_SCHEME_V1)) return value // legacy plaintext
        return runCatching { decrypt(value) }
            .onFailure { Timber.w(it, "SecretBox.tryDecrypt failed; returning raw value to avoid data loss") }
            .getOrDefault(value)
    }

    private fun aead(): Aead {
        cachedAead?.let { return it }
        synchronized(initLock) {
            cachedAead?.let { return it }
            val fresh = initializeAead()
            cachedAead = fresh
            return fresh
        }
    }

    private fun initializeAead(): Aead {
        AeadConfig.register()
        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return handle.getPrimitive(Aead::class.java)
    }

    private companion object {
        const val MASTER_KEY_URI = "android-keystore://lisvpn_master_key_v1"
        const val KEYSET_NAME = "lisvpn_secrets_v1"
        const val PREF_FILE = "lisvpn_secrets_pref"
        const val KEY_TEMPLATE = "AES256_GCM"
    }
}
