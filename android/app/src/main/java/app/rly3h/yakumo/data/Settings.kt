package app.rly3h.yakumo.data

import android.content.Context
import app.rly3h.yakumo.ui.session.VadParams

/** Lightweight user settings backed by SharedPreferences (no extra deps). */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var speechRate: Float
    get() = prefs.getFloat(KEY_RATE, 1.0f)
    set(v) = prefs.edit().putFloat(KEY_RATE, v).apply()

  var autoSpeak: Boolean
    get() = prefs.getBoolean(KEY_AUTO_SPEAK, true)
    set(v) = prefs.edit().putBoolean(KEY_AUTO_SPEAK, v).apply()

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

  private companion object {
    const val KEY_RATE = "speechRate"
    const val KEY_AUTO_SPEAK = "autoSpeak"
    const val KEY_VAD_THRESH = "vadThreshold"
    const val KEY_VAD_HANG = "vadHangMs"
    const val KEY_VAD_MIN = "vadMinVoicedMs"
    const val KEY_VAD_MAX = "vadMaxSegMs"
  }
}
