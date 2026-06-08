package app.rly3h.yakumo.data

import android.content.Context

/** Lightweight user settings backed by SharedPreferences (no extra deps). */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var speechRate: Float
    get() = prefs.getFloat(KEY_RATE, 1.0f)
    set(v) = prefs.edit().putFloat(KEY_RATE, v).apply()

  var autoSpeak: Boolean
    get() = prefs.getBoolean(KEY_AUTO_SPEAK, true)
    set(v) = prefs.edit().putBoolean(KEY_AUTO_SPEAK, v).apply()

  private companion object {
    const val KEY_RATE = "speechRate"
    const val KEY_AUTO_SPEAK = "autoSpeak"
  }
}
