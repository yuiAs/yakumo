package app.rly3h.yakumo.translate

import kotlinx.coroutines.CoroutineScope

/**
 * One conversation turn as shown in the session UI: the recognized source text
 * and (once ready) its translation. `translation == null` means "still in
 * flight" — the row renders the transcript immediately and fills the translation
 * in later. Shared by the offline and online engines so the screen is unchanged.
 *
 * @param srcLang/tgtLang FLORES-200 codes (the UI maps them to badges/locales)
 * @param detected the raw ASR language tag, empty when the engine doesn't report one
 */
data class LiveTurn(
  val id: Long,
  val transcript: String,
  val srcLang: String,
  val tgtLang: String,
  val detected: String,
  val translation: String? = null,
)

/**
 * The seam between an engine and the session screen. Each method maps 1:1 onto a
 * single piece of screen state, so an engine drives the UI without knowing about
 * Compose. Implementations may invoke these from background threads; the screen's
 * adapter is responsible for thread-safe state updates.
 */
interface TranslatorCallbacks {
  /** A new turn began; append it (transcript shown, translation pending). */
  fun onTurnStart(turn: LiveTurn)

  /** Patch an existing turn in place (streaming transcript and/or translation). */
  fun onTurnUpdate(id: Long, transcript: String? = null, translation: String? = null)

  /** Live, not-yet-finalized transcript (streaming recognizers); "" clears it. */
  fun onPartial(text: String)

  /** Human-readable status line under the mic. */
  fun onStatus(text: String)

  /** Speak [text] in [tgtFlores] via the OS TTS (offline path only). */
  fun onSpeak(text: String, tgtFlores: String)

  /** Persist the session after a turn completes. */
  fun onPersist()

  /** The engine stopped; [error] is non-null if it ended abnormally. */
  fun onFinished(error: String?)
}

/**
 * A pluggable speech-translation engine. `start` is non-blocking and spawns its
 * work on [scope]; `stop` requests a clean shutdown (idempotent).
 */
interface SpeechTranslator {
  fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks)
  fun stop()
}
