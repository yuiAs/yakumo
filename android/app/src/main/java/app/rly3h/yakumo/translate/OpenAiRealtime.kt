package app.rly3h.yakumo.translate

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Shared OpenAI Realtime translation endpoints + ephemeral minting. */
object OpenAiRealtime {
  const val WS_URL = "wss://api.openai.com/v1/realtime/translations"
  const val CLIENT_SECRETS_URL = "https://api.openai.com/v1/realtime/translations/client_secrets"
  const val MODEL = "gpt-realtime-translate"
  const val SAFETY_ID = "yakumo-app-user"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Exchanges the user's long-lived API key for a short-lived (600 s) ephemeral
   * secret used to authenticate the WebSocket. The create call requires a
   * `session` with the model; [language] pins the translation target (OpenAI
   * code like "ja"/"en") when known. Blocking — call off the main thread. Throws
   * with the response body on failure.
   */
  fun mintEphemeral(apiKey: String, language: String? = null): String {
    val client = OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()
    val audio = if (language != null) ""","audio":{"output":{"language":"$language"}}""" else ""
    val body = (
      """{"expires_after":{"anchor":"created_at","seconds":600},""" +
        """"session":{"model":"$MODEL"$audio}}"""
      ).toRequestBody("application/json".toMediaType())
    val req = Request.Builder()
      .url(CLIENT_SECRETS_URL)
      .header("Authorization", "Bearer $apiKey")
      .header("OpenAI-Safety-Identifier", SAFETY_ID)
      .post(body)
      .build()
    client.newCall(req).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
      return extractSecret(text) ?: error("No ephemeral secret in response: ${text.take(300)}")
    }
  }

  // The response shape isn't pinned; look for the value in the known places.
  private fun extractSecret(bodyText: String): String? {
    val root = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull() ?: return null
    root["value"]?.jsonPrimitive?.contentOrNull?.let { return it }
    (root["client_secret"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull?.let { return it }
    root["client_secret"]?.jsonPrimitive?.contentOrNull?.let { return it }
    return null
  }
}
