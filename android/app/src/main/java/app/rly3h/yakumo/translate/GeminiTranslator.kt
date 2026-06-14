package app.rly3h.yakumo.translate

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_16K
import app.rly3h.yakumo.ui.session.floresToLiveLang
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.resolveDirection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Online engine: Gemini Live Translate speech-to-speech translation over the
 * BidiGenerateContent WebSocket.
 *
 * Flow: open `wss://…/BidiGenerateContent?key=<apiKey>` → send a `setup` frame
 * pinning the translation target → on `setupComplete`, push continuous 16 kHz
 * PCM16 mic audio (`realtimeInput.audio`) → receive `serverContent` with input/
 * output transcription (rendered into one turn row) and translated audio
 * (24 kHz PCM16, played via AudioTrack). The model detects the source language;
 * we only pin the target. MVP is one-directional — the direction picks the target.
 */
internal class GeminiTranslator(
  private val context: Context,
  private val settings: Settings,
  private val pair: LanguagePair,
  private val inputMode: InputMode,
) : SpeechTranslator {
  private val running = AtomicBoolean(false)

  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var failure: String? = null
  // Completes on `setupComplete` (true) or a failed open (false). Capture waits
  // on it so audio isn't sent before the session is configured.
  private val ready = CompletableDeferred<Boolean>()
  private val finished = CompletableDeferred<Unit>()

  // Fixed direction for the session: target language is pinned, source auto-detected.
  private val resolved = resolveDirection(pair, inputMode, "", "")
  private val srcFlores = resolved.first.flores
  private val tgtFlores = resolved.second.flores

  private val audio = RealtimeAudioSink { settings.autoSpeak }

  private val json = Json { ignoreUnknownKeys = true }

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    val cb = callbacks
    val sink = RealtimeTurnAssembler(srcFlores, tgtFlores, cb)
    scope.launch(Dispatchers.IO) {
      try {
        val key = settings.apiKey(context, OnlineProvider.GEMINI)
        if (key.isNullOrBlank()) {
          cb.onFinished("No API key set. Add a Gemini key in Settings → Online.")
          return@launch
        }
        cb.onStatus("Connecting to Gemini…")

        val client = OkHttpClient.Builder()
          .pingInterval(20, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
          .build()
        val request = Request.Builder()
          .url("${GeminiLive.WS_URL}?key=$key")
          .build()
        webSocket = client.newWebSocket(request, listener(cb, sink))

        // Playback can start immediately; capture gates on `setupComplete`.
        launch(Dispatchers.IO) { audio.playbackLoop() }
        launch(Dispatchers.IO) { captureLoop(cb) }
        launch(Dispatchers.IO) { idleWatchdog(sink) }

        finished.await()
        cb.onFinished(failure)
      } catch (e: Throwable) {
        cb.onFinished(e.message ?: e.toString())
      } finally {
        teardown()
      }
    }
  }

  override fun stop() {
    if (!running.compareAndSet(true, false)) return
    runCatching { webSocket?.close(1000, "client stop") }
    // Give the socket a moment to flush trailing deltas, then unblock start().
    if (!finished.isCompleted) finished.complete(Unit)
  }

  // --- WebSocket events ---

  private fun listener(cb: TranslatorCallbacks, sink: RealtimeTurnAssembler) = object : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
      ws.send(GeminiLive.setupMessage(floresToLiveLang(tgtFlores)))
    }

    // The Live API delivers its JSON responses as binary frames; OkHttp routes
    // those here, not to the String overload. Decode and feed the same handler.
    override fun onMessage(ws: WebSocket, bytes: ByteString) {
      handleEvent(bytes.utf8(), cb, sink)
    }

    override fun onMessage(ws: WebSocket, text: String) {
      handleEvent(text, cb, sink)
    }

    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
      // Gemini reports fatal setup/quota errors via the close frame.
      if (running.get() && code != 1000 && reason.isNotBlank()) failure = "Gemini error: $reason"
      sink.finalize()
      if (!ready.isCompleted) ready.complete(false)
      if (!finished.isCompleted) finished.complete(Unit)
    }

    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
      // A failed WS upgrade carries the server's reason (bad model/key/quota) in
      // the HTTP response, not the throwable — surface the code + body snippet.
      val http = response?.let { resp ->
        val body = runCatching { resp.body?.string() }.getOrNull().orEmpty().take(300)
        "HTTP ${resp.code}${if (body.isNotBlank()) ": $body" else ""}"
      }
      val detail = http ?: t.message ?: t.toString()
      Log.e(TAG, "WebSocket failure: $detail", t)
      if (running.get()) failure = "Gemini error: $detail"
      if (!ready.isCompleted) ready.complete(false)
      if (!finished.isCompleted) finished.complete(Unit)
    }
  }

  private fun handleEvent(text: String, cb: TranslatorCallbacks, sink: RealtimeTurnAssembler) {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    if (obj.containsKey("setupComplete")) {
      cb.onStatus("Listening (Gemini → ${labelOf(tgtFlores)})…")
      if (!ready.isCompleted) ready.complete(true)
      return
    }
    (obj["serverContent"] as? JsonObject)?.let { handleServerContent(it, sink) }
  }

  private fun handleServerContent(content: JsonObject, sink: RealtimeTurnAssembler) {
    (content["inputTranscription"] as? JsonObject)?.let {
      sink.sourceDelta(textOf(it), detectedLang = langOf(it))
    }
    (content["outputTranscription"] as? JsonObject)?.let {
      sink.targetDelta(textOf(it))
    }
    // A server event can carry multiple parts at once — drain audio from all of them.
    (content["modelTurn"] as? JsonObject)?.get("parts")?.let { parts ->
      runCatching { parts.jsonArray }.getOrNull()?.forEach { part ->
        val data = (part as? JsonObject)?.get("inlineData")?.jsonObject
          ?.get("data")?.jsonPrimitive?.contentOrNull
        if (data != null) audio.enqueueBase64(data)
      }
    }
    if (content["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) sink.finalize()
  }

  private fun textOf(obj: JsonObject): String = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun langOf(obj: JsonObject): String = obj["languageCode"]?.jsonPrimitive?.contentOrNull.orEmpty()

  // No `turnComplete` for a while after the last delta → close the open turn.
  private suspend fun idleWatchdog(sink: RealtimeTurnAssembler) {
    while (running.get()) {
      kotlinx.coroutines.delay(IDLE_POLL_MS)
      sink.finalizeIfIdle(IDLE_GAP_MS)
    }
  }

  // --- Audio in (16 kHz PCM16, the Live API's native input rate) ---

  private suspend fun captureLoop(cb: TranslatorCallbacks) {
    if (!ready.await()) return // session never came up
    if (!running.get()) return
    try {
      rawChunkCaptureLoop(running, SAMPLE_RATE_16K, MediaRecorder.AudioSource.VOICE_COMMUNICATION) { chunk ->
        val ws = webSocket ?: return@rawChunkCaptureLoop
        // Drop audio rather than build an unbounded send queue on a stalled link.
        if (ws.queueSize() > MAX_WS_QUEUE_BYTES) return@rawChunkCaptureLoop
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        ws.send("""{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=16000"}}}""")
      }
    } catch (e: Throwable) {
      if (running.get()) cb.onStatus("Mic error: ${e.message}")
    }
  }

  private fun teardown() {
    audio.close()
    runCatching { webSocket?.cancel() }
    webSocket = null
  }

  private fun labelOf(flores: String): String = floresToLiveLang(flores).uppercase()

  private companion object {
    const val TAG = "GeminiTranslator"
    const val MAX_WS_QUEUE_BYTES = 256 * 1024L
    const val IDLE_POLL_MS = 300L
    const val IDLE_GAP_MS = 1200L
  }
}
