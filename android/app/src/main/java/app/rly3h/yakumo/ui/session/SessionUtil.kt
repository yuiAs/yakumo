package app.rly3h.yakumo.ui.session

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.Locale

// Script heuristic: kana/kanji -> Japanese. Fallback when ASR gives no language.
internal fun isJapanese(text: String): Boolean =
  text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

/**
 * A language the translation pair can be set to. The single source of truth for
 * everything language-specific; supporting one more language is one more row.
 *
 * @param flores NLLB FLORES-200 code passed to the translator
 * @param asrTag substring of the SenseVoice tag ("<|en|>" contains "en")
 * @param label  full name shown in the picker
 * @param short  two-letter badge shown on a turn card
 * @param locale OS TTS locale used to read the translation aloud
 */
internal data class LanguageOption(
  val flores: String,
  val asrTag: String,
  val label: String,
  val short: String,
  val locale: Locale,
)

// Currently EN<->JA only; both directions of every pair must be ASR-recognizable
// (SenseVoice) and ideally TTS-speakable (OS). Add a row to widen the set.
internal val LANGUAGES: List<LanguageOption> = listOf(
  LanguageOption("eng_Latn", "en", "English", "EN", Locale.ENGLISH),
  LanguageOption("jpn_Jpan", "ja", "Japanese", "JA", Locale.JAPANESE),
)

internal fun languageByFlores(flores: String): LanguageOption =
  LANGUAGES.firstOrNull { it.flores == flores } ?: LANGUAGES.first()

/** The two languages of a conversation; auto-swap translates between them. */
internal data class LanguagePair(val a: LanguageOption, val b: LanguageOption)

/** How the spoken side of the pair is chosen for an utterance. */
internal enum class InputMode { AUTO, FORCE_A, FORCE_B }

/**
 * Decide (source, target) for one utterance in the bidirectional pair.
 * AUTO detects which side was spoken (ASR tag first, kana/kanji script as a
 * fallback) and translates to the other; FORCE_* overrides that detection.
 * Unrecognized input defaults to a -> b.
 */
internal fun resolveDirection(
  pair: LanguagePair,
  mode: InputMode,
  asrTag: String,
  transcript: String,
): Pair<LanguageOption, LanguageOption> = when (mode) {
  InputMode.FORCE_A -> pair.a to pair.b
  InputMode.FORCE_B -> pair.b to pair.a
  InputMode.AUTO -> if (detectSide(pair, asrTag, transcript) == pair.b) pair.b to pair.a else pair.a to pair.b
}

private fun detectSide(pair: LanguagePair, asrTag: String, transcript: String): LanguageOption {
  // Trust the ASR tag when present, but only if it names a side of this pair.
  if (asrTag.isNotBlank()) {
    listOf(pair.a, pair.b).firstOrNull { asrTag.contains(it.asrTag) }?.let { return it }
  }
  // No usable tag (e.g. the English-only streaming recognizer): fall back to a
  // script check, which can only distinguish Japanese.
  val jp = listOf(pair.a, pair.b).firstOrNull { it.flores == "jpn_Jpan" }
  return if (jp != null && isJapanese(transcript)) jp else pair.a
}

// FLORES code -> Locale for the OS TTS engine.
internal fun localeFromFlores(code: String): Locale = languageByFlores(code).locale

internal fun labelForFlores(code: String): String = languageByFlores(code).short

// --- Continuous capture ---
// 16 kHz mono. Both the segmented (Silero VAD) and streaming-ASR paths consume
// fixed ~100 ms PCM16 chunks; segmentation happens downstream (Silero on the
// Rust side, or sherpa's online endpointer), not in the capture loop.
private const val SAMPLE_RATE = 16000

/**
 * User-tunable Silero VAD knobs (persisted in Settings). `threshold` is the
 * speech-probability cutoff (0..1; lower = more sensitive); the durations are in
 * milliseconds here for the sliders and converted to seconds at the FFI boundary.
 */
data class VadParams(
  val threshold: Float = 0.5f, // speech probability; above = voiced
  val minSilenceMs: Int = 500, // trailing silence that ends a segment
  val minSpeechMs: Int = 250, // ignore blips shorter than this
  val maxSpeechMs: Int = 15000, // force-cut very long utterances
)

// Sensible, processing-appropriate bounds for the Settings sliders.
object VadBounds {
  val threshold = 0.1f..0.9f
  val minSilenceMs = 100f..1500f
  val minSpeechMs = 50f..1000f
  val maxSpeechMs = 5000f..30000f
}

/**
 * Endpoint rules for the streaming recognizer (seconds). These map to sherpa's
 * rule1/2/3 and are baked into the recognizer at creation. `rule2` (trailing
 * silence after speech) is the main "end of turn" knob.
 */
data class EndpointParams(
  val rule1: Float = 2.4f, // trailing silence before any speech is decoded
  val rule2: Float = 1.2f, // trailing silence after speech -> ends a turn
  val rule3: Float = 20.0f, // max utterance length, force-cut
)

object EndpointBounds {
  val rule1 = 0.5f..5.0f
  val rule2 = 0.3f..3.0f
  val rule3 = 5.0f..30.0f
}

private fun frameToLeBytes(frame: ShortArray, n: Int): ByteArray {
  val b = ByteArray(n * 2)
  for (i in 0 until n) {
    val s = frame[i].toInt()
    b[i * 2] = (s and 0xFF).toByte()
    b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
  }
  return b
}

private const val STREAM_CHUNK_SAMPLES = 1600 // 100 ms at 16 kHz

/**
 * Continuous capture emitting fixed ~100 ms PCM16 chunks until `running` goes
 * false. Used by both the segmented path (chunks fed to Silero VAD, which emits
 * speech segments) and the streaming recognizer (its own endpointer). Blocking —
 * run on IO.
 */
internal fun rawChunkCaptureLoop(
  running: java.util.concurrent.atomic.AtomicBoolean,
  emit: (ByteArray) -> Unit,
) {
  val minBuf = AudioRecord.getMinBufferSize(
    SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
  )
  val record = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuf, SAMPLE_RATE * 2),
  )
  val frame = ShortArray(STREAM_CHUNK_SAMPLES)
  try {
    record.startRecording()
    while (running.get()) {
      val n = record.read(frame, 0, frame.size)
      if (n > 0) emit(frameToLeBytes(frame, n))
    }
  } finally {
    record.stop()
    record.release()
  }
}
