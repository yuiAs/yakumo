package app.rly3h.yakumo.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts a single secret (the OpenAI API key) at rest using Google Tink AEAD.
 *
 * Tink keeps its own AES256-GCM data key in a dedicated SharedPreferences file,
 * wrapped by a master key held in the Android Keystore (hardware-backed where
 * available). We persist only the base64 ciphertext of the secret in the regular
 * settings prefs. This replaces the deprecated EncryptedSharedPreferences while
 * keeping the surface tiny — no DataStore/proto needed for one string.
 */
object SecureKeyStore {
  private const val KEYSET_NAME = "yakumo_tink_keyset"
  private const val PREF_FILE = "yakumo_tink_prefs"
  private const val MASTER_KEY_URI = "android-keystore://yakumo_master_key"
  // Associated data binds the ciphertext to this app/purpose (not secret).
  private val AAD = "yakumo.openai.apikey".toByteArray(Charsets.UTF_8)

  @Volatile private var cached: Aead? = null

  private fun aead(context: Context): Aead {
    cached?.let { return it }
    return synchronized(this) {
      cached ?: run {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
          .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE)
          .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
          .withMasterKeyUri(MASTER_KEY_URI)
          .build()
          .keysetHandle
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also { cached = it }
      }
    }
  }

  /** Encrypts [plaintext] and returns base64 (NO_WRAP) ciphertext. */
  fun encrypt(context: Context, plaintext: String): String {
    val ct = aead(context).encrypt(plaintext.toByteArray(Charsets.UTF_8), AAD)
    return Base64.encodeToString(ct, Base64.NO_WRAP)
  }

  /**
   * Decrypts base64 ciphertext. Returns null on any failure (corrupt data, a
   * lost keyset after a device transfer, cleared Keystore) so callers treat it
   * as "no key set" and prompt re-entry rather than crashing.
   */
  fun decrypt(context: Context, b64: String): String? = runCatching {
    val ct = Base64.decode(b64, Base64.NO_WRAP)
    String(aead(context).decrypt(ct, AAD), Charsets.UTF_8)
  }.getOrNull()
}
