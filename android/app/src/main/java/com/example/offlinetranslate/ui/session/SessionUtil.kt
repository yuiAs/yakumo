package com.example.offlinetranslate.ui.session

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

/** Records mono 16 kHz signed-16-bit PCM from the mic and returns it as bytes. */
internal fun recordPcm16(durationMs: Long): ByteArray {
  val sampleRate = 16000
  val minBuf = AudioRecord.getMinBufferSize(
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
  )
  val record = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuf, sampleRate * 2),
  )
  val out = java.io.ByteArrayOutputStream()
  val buf = ByteArray(4096)
  try {
    record.startRecording()
    val end = System.currentTimeMillis() + durationMs
    while (System.currentTimeMillis() < end) {
      val n = record.read(buf, 0, buf.size)
      if (n > 0) out.write(buf, 0, n)
    }
  } finally {
    record.stop()
    record.release()
  }
  return out.toByteArray()
}
