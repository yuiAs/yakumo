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

  // --- VAD knobs (defaults come from VadParams) ---
  private val def = VadParams()

  var vadThreshold: Float
    get() = prefs.getFloat(KEY_VAD_THRESH, def.thresholdRms.toFloat())
    set(v) = prefs.edit().putFloat(KEY_VAD_THRESH, v).apply()

  var vadHangMs: Int
    get() = prefs.getInt(KEY_VAD_HANG, def.hangMs)
    set(v) = prefs.edit().putInt(KEY_VAD_HANG, v).apply()

  var vadMinVoicedMs: Int
    get() = prefs.getInt(KEY_VAD_MIN, def.minVoicedMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MIN, v).apply()

  var vadMaxSegMs: Int
    get() = prefs.getInt(KEY_VAD_MAX, def.maxSegMs)
    set(v) = prefs.edit().putInt(KEY_VAD_MAX, v).apply()

  fun vadParams(): VadParams =
    VadParams(
      thresholdRms = vadThreshold.toDouble(),
      hangMs = vadHangMs,
      minVoicedMs = vadMinVoicedMs,
      maxSegMs = vadMaxSegMs,
    )

  /** Restores the VAD knobs to their defaults. */
  fun resetVad() {
    vadThreshold = def.thresholdRms.toFloat()
    vadHangMs = def.hangMs
    vadMinVoicedMs = def.minVoicedMs
    vadMaxSegMs = def.maxSegMs
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
    const val KEY_LANG_A = "langAFlores"
    const val KEY_LANG_B = "langBFlores"
    const val KEY_INPUT_MODE = "inputMode"
    const val KEY_VAD_THRESH = "vadThreshold"
    const val KEY_VAD_HANG = "vadHangMs"
    const val KEY_VAD_MIN = "vadMinVoicedMs"
    const val KEY_VAD_MAX = "vadMaxSegMs"
    const val KEY_EP_RULE1 = "endpointRule1"
    const val KEY_EP_RULE2 = "endpointRule2"
    const val KEY_EP_RULE3 = "endpointRule3"
  }
}
