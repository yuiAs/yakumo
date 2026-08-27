package app.rly3h.yakumo.translate

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared Gemini Live API endpoints + the Live Translate session setup.
 *
 * Unlike OpenAI Realtime there is no ephemeral-token exchange: the Live API
 * authenticates the WebSocket with the API key as a `key` query parameter, so the
 * stored key is used directly at connect time. Translation is configured once in
 * the opening `setup` message; audio then streams both ways.
 */
object GeminiLive {
  const val WS_URL =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
  const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
  const val MODEL = "models/gemini-3.5-live-translate-preview"

  /**
   * Opening `setup` frame for a Live Translate session. Pins the translation
   * target ([targetLang] is "ja"/"en"); the source is auto-detected. Both
   * transcriptions are enabled so we can show the original text alongside the
   * translation. `echoTargetLanguage:false` keeps the model silent when the input
   * is already in the target language — so the speaker doesn't hear themselves
   * parroted back in the one-directional MVP.
   */
  fun setupMessage(targetLang: String): String =
    """{"setup":{"model":"$MODEL",""" +
      """"generationConfig":{"responseModalities":["AUDIO"],""" +
      """"translationConfig":{"targetLanguageCode":"$targetLang","echoTargetLanguage":false}},""" +
      // inputAudioTranscription/outputAudioTranscription are setup-level fields,
      // not generationConfig members (the server rejects them under generation_config).
      """"inputAudioTranscription":{},"outputAudioTranscription":{}}}"""

  /**
   * Validates the key with a lightweight REST call (`GET /v1beta/models?key=…`).
   * Blocking — call off the main thread. Throws with the response body on failure.
   */
  fun testKey(apiKey: String) {
    val client = OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()
    val req = Request.Builder().url("$MODELS_URL?key=$apiKey").get().build()
    client.newCall(req).execute().use { resp ->
      val text = resp.body.string()
      if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
    }
  }
}
