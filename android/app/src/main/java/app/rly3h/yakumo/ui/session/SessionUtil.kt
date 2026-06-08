package app.rly3h.yakumo.ui.session

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.Locale

// Script heuristic: kana/kanji -> Japanese. Fallback when ASR gives no language.
internal fun isJapanese(text: String): Boolean =
  text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

// SenseVoice tag ("<|en|>", "<|ja|>", ...) -> FLORES source code for the EN<->JA
// pair. Null for other/empty tags so the caller falls back to the heuristic.
internal fun floresFromAsrLang(lang: String): String? = when {
  lang.contains("ja") -> "jpn_Jpan"
  lang.contains("en") -> "eng_Latn"
  else -> null
}

// FLORES target code -> Locale for the OS TTS engine.
internal fun localeFromFlores(code: String): Locale =
  if (code.startsWith("jpn")) Locale.JAPANESE else Locale.ENGLISH

internal fun labelForFlores(code: String): String = when {
  code.startsWith("jpn") -> "JA"
  code.startsWith("eng") -> "EN"
  else -> code
}

// --- Energy-VAD continuous capture ---
// 16 kHz mono; 20 ms frames. Tuned for assist-style conversation: cut a segment
// after a short pause so it can be translated while the user keeps talking.
private const val SAMPLE_RATE = 16000
private const val FRAME_SAMPLES = 320 // 20 ms
private const val PRE_FRAMES = 10 // ~200 ms pre-roll so onsets aren't clipped

/** User-tunable VAD knobs (persisted in Settings). Defaults are the prior consts. */
data class VadParams(
  val thresholdRms: Double = 600.0, // int16 RMS; above = voiced
  val hangMs: Int = 1000, // trailing silence that ends a segment
  val minVoicedMs: Int = 500, // ignore blips shorter than this
  val maxSegMs: Int = 8000, // force-cut very long utterances
)

// Sensible, processing-appropriate bounds for the Settings sliders.
object VadBounds {
  val threshold = 200f..2000f
  val hangMs = 300f..2000f
  val minVoicedMs = 100f..1000f
  val maxSegMs = 5000f..30000f
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

private fun ShortArray.rms(n: Int): Double {
  var sum = 0.0
  for (i in 0 until n) {
    val v = this[i].toDouble()
    sum += v * v
  }
  return kotlin.math.sqrt(sum / n)
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

/**
 * Records continuously until `running` goes false, emitting one PCM16 segment
 * per detected utterance (split on trailing silence). Blocking — run on IO.
 */
internal fun captureLoop(
  running: java.util.concurrent.atomic.AtomicBoolean,
  vad: VadParams,
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
  val frame = ShortArray(FRAME_SAMPLES)
  val seg = java.io.ByteArrayOutputStream()
  val pre = ArrayDeque<ByteArray>()
  var speaking = false
  var voicedMs = 0
  var silentMs = 0

  fun cut() {
    if (voicedMs >= vad.minVoicedMs) emit(seg.toByteArray())
    seg.reset()
    speaking = false
    voicedMs = 0
    silentMs = 0
  }

  try {
    record.startRecording()
    while (running.get()) {
      val n = record.read(frame, 0, frame.size)
      if (n <= 0) continue
      val bytes = frameToLeBytes(frame, n)
      val frameMs = n * 1000 / SAMPLE_RATE
      if (frame.rms(n) > vad.thresholdRms) {
        if (!speaking) {
          speaking = true
          voicedMs = 0
          silentMs = 0
          while (pre.isNotEmpty()) seg.write(pre.removeFirst())
        }
        seg.write(bytes)
        voicedMs += frameMs
        silentMs = 0
      } else if (speaking) {
        seg.write(bytes)
        silentMs += frameMs
        if (silentMs >= vad.hangMs) cut()
      } else {
        pre.addLast(bytes)
        if (pre.size > PRE_FRAMES) pre.removeFirst()
      }
      if (speaking && voicedMs + silentMs >= vad.maxSegMs) cut()
    }
    if (speaking) cut() // flush the final segment on stop
  } finally {
    record.stop()
    record.release()
  }
}

private const val STREAM_CHUNK_SAMPLES = 1600 // 100 ms at 16 kHz

/**
 * Continuous capture for the streaming recognizer: emits fixed ~100 ms PCM16
 * chunks until `running` goes false. No VAD here — the online recognizer does
 * its own endpoint detection on the decoded stream. Blocking — run on IO.
 */
internal fun streamingCaptureLoop(
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
