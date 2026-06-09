package app.rly3h.yakumo.data

import android.content.Context
import app.rly3h.yakumo.ui.session.EndpointParams
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.VadParams
import app.rly3h.yakumo.ui.session.languageByFlores

/** Lightweight user settings backed by SharedPreferences (no extra deps). */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var speechRate: Float
    get() = prefs.getFloat(KEY_RATE, 1.0f)
    set(v) = prefs.edit().putFloat(KEY_RATE, v).apply()

  var autoSpeak: Boolean
    get() = prefs.getBoolean(KEY_AUTO_SPEAK, true)
    set(v) = prefs.edit().putBoolean(KEY_AUTO_SPEAK, v).apply()

  // Experimental: use the streaming (nemotron-en) recognizer for live partial
  // transcripts. English-only; off by default so the SenseVoice JA path is kept.
  var streamingAsr: Boolean
    get() = prefs.getBoolean(KEY_STREAMING_ASR, false)
    set(v) = prefs.edit().putBoolean(KEY_STREAMING_ASR, v).apply()

  // --- Online mode (OpenAI Realtime) ---
  // Last-used engine toggle (mic-side switch). Offline by default; only honored
  // when an API key is set and the network is up (checked at the call site).
  var onlineEnabled: Boolean
    get() = prefs.getBoolean(KEY_ONLINE, false)
    set(v) = prefs.edit().putBoolean(KEY_ONLINE, v).apply()

  // The API key is stored only as Tink ciphertext; the plaintext never touches prefs.
  private var apiKeyCipher: String?
    get() = prefs.getString(KEY_API_KEY_CIPHER, null)
    set(v) = prefs.edit().apply { if (v == null) remove(KEY_API_KEY_CIPHER) else putString(KEY_API_KEY_CIPHER, v) }.apply()

  fun hasApiKey(): Boolean = !apiKeyCipher.isNullOrBlank()

  fun setApiKey(context: Context, plaintext: String) {
    apiKeyCipher = SecureKeyStore.encrypt(context, plaintext)
  }

  /** Decrypted API key, or null if unset/undecryptable (treated as "no key"). */
  fun apiKey(context: Context): String? = apiKeyCipher?.let { SecureKeyStore.decrypt(context, it) }

  fun clearApiKey() {
    apiKeyCipher = null
  }

  // --- Conversation language pair + input override (FLORES codes / enum name) ---
  var langAFlores: String
    get() = prefs.getString(KEY_LANG_A, "eng_Latn")!!
    set(v) = prefs.edit().putString(KEY_LANG_A, v).apply()

  var langBFlores: String
    get() = prefs.getString(KEY_LANG_B, "jpn_Jpan")!!
    set(v) = prefs.edit().putString(KEY_LANG_B, v).apply()

  internal var inputMode: InputMode
    get() = runCatching { InputMode.valueOf(prefs.getString(KEY_INPUT_MODE, null)!!) }
      .getOrDefault(InputMode.AUTO)
    set(v) = prefs.edit().putString(KEY_INPUT_MODE, v.name).apply()

  internal fun languagePair(): LanguagePair =
    LanguagePair(languageByFlores(langAFlores), languageByFlores(langBFlores))

  // --- Silero VAD knobs (defaults come from VadParams) ---
  private val def = VadParams()

  var vadThreshold: Float
    get() = prefs.getFloat(KEY_VAD_THRESH, def.threshold)
    set(v) = prefs.edit().putFloat(KEY_VAD_THRESH, v).apply()

  var vadMinSilenceMs: Int
    get() = prefs.getInt(KEY_VAD_SILENCE, def.minSilenceMs)
    set(v) = prefs.edit().putInt(KEY_VAD_SILENCE, v).apply()

  var vadMinSpeechMs: Int
    get() = prefs.getInt(KEY_VAD_MIN_SPEECH, def.minSpeechMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MIN_SPEECH, v).apply()

  var vadMaxSpeechMs: Int
    get() = prefs.getInt(KEY_VAD_MAX_SPEECH, def.maxSpeechMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MAX_SPEECH, v).apply()

  fun vadParams(): VadParams =
    VadParams(
      threshold = vadThreshold,
      minSilenceMs = vadMinSilenceMs,
      minSpeechMs = vadMinSpeechMs,
      maxSpeechMs = vadMaxSpeechMs,
    )

  /** Restores the VAD knobs to their defaults. */
  fun resetVad() {
    vadThreshold = def.threshold
    vadMinSilenceMs = def.minSilenceMs
    vadMinSpeechMs = def.minSpeechMs
    vadMaxSpeechMs = def.maxSpeechMs
  }

  // --- Streaming endpoint rules (defaults come from EndpointParams) ---
  private val epDef = EndpointParams()

  var endpointRule1: Float
    get() = prefs.getFloat(KEY_EP_RULE1, epDef.rule1)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE1, v).apply()

  var endpointRule2: Float
    get() = prefs.getFloat(KEY_EP_RULE2, epDef.rule2)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE2, v).apply()

  var endpointRule3: Float
    get() = prefs.getFloat(KEY_EP_RULE3, epDef.rule3)
    set(v) = prefs.edit().putFloat(KEY_EP_RULE3, v).apply()

  fun endpointParams(): EndpointParams =
    EndpointParams(rule1 = endpointRule1, rule2 = endpointRule2, rule3 = endpointRule3)

  /** Restores the endpoint rules to their defaults. */
  fun resetEndpoint() {
    endpointRule1 = epDef.rule1
    endpointRule2 = epDef.rule2
    endpointRule3 = epDef.rule3
  }

  private companion object {
    const val KEY_RATE = "speechRate"
    const val KEY_AUTO_SPEAK = "autoSpeak"
    const val KEY_STREAMING_ASR = "streamingAsr"
    const val KEY_ONLINE = "onlineEnabled"
    const val KEY_API_KEY_CIPHER = "apiKeyCipher"
    const val KEY_LANG_A = "langAFlores"
    const val KEY_LANG_B = "langBFlores"
    const val KEY_INPUT_MODE = "inputMode"
    // Silero-era keys (distinct from the old RMS knobs so stale values don't leak).
    const val KEY_VAD_THRESH = "vadProbThreshold"
    const val KEY_VAD_SILENCE = "vadMinSilenceMs"
    const val KEY_VAD_MIN_SPEECH = "vadMinSpeechMs"
    const val KEY_VAD_MAX_SPEECH = "vadMaxSpeechMs"
    const val KEY_EP_RULE1 = "endpointRule1"
    const val KEY_EP_RULE2 = "endpointRule2"
    const val KEY_EP_RULE3 = "endpointRule3"
  }
}
