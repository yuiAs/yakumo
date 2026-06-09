package app.rly3h.yakumo.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.InputMode
import app.rly3h.yakumo.ui.session.LanguagePair
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_16K
import app.rly3h.yakumo.ui.session.SAMPLE_RATE_24K
import app.rly3h.yakumo.ui.session.floresToOpenAiLang
import app.rly3h.yakumo.ui.session.linearResample16to24
import app.rly3h.yakumo.ui.session.rawChunkCaptureLoop
import app.rly3h.yakumo.ui.session.resolveDirection
import app.rly3h.yakumo.ui.session.supportsCaptureRate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Online engine: OpenAI Realtime speech-to-speech translation over a WebSocket.
 *
 * Flow: mint a short-lived ephemeral secret from the user's stored key → open
 * `wss://.../v1/realtime/translations` → push continuous 24 kHz PCM16 mic audio →
 * receive source/target transcript deltas (rendered into one turn row) and
 * translated-audio deltas (played via AudioTrack). The Realtime model detects the
 * source language itself; we only pin the target. MVP is one-directional — the
 * direction toggle picks the target language.
 */
internal class OnlineTranslator(
  private val context: Context,
  private val settings: Settings,
  private val pair: LanguagePair,
  private val inputMode: InputMode,
) : SpeechTranslator {
  private val running = AtomicBoolean(false)
  private val nextId = AtomicLong(0L)

  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var failure: String? = null
  private val opened = CompletableDeferred<Boolean>()
  private val finished = CompletableDeferred<Unit>()

  // Translated-audio deltas are decoded on the WS thread and drained by a single
  // blocking-write consumer so a slow AudioTrack write never stalls the socket.
  private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

  // Turn assembly state (touched from the WS thread and the idle watchdog).
  private val lock = Any()
  private var currentTurnId: Long? = null
  private var srcAcc = StringBuilder()
  private var tgtAcc = StringBuilder()
  @Volatile private var lastDeltaAt = 0L

  // Fixed direction for the session: target language is pinned, source auto-detected.
  private val resolved = resolveDirection(pair, inputMode, "", "")
  private val srcFlores = resolved.first.flores
  private val tgtFlores = resolved.second.flores

  private val json = Json { ignoreUnknownKeys = true }

  override fun start(scope: CoroutineScope, callbacks: TranslatorCallbacks) {
    running.set(true)
    scope.launch(Dispatchers.IO) {
      try {
        val key = settings.apiKey(context)
        if (key.isNullOrBlank()) {
          callbacks.onFinished("No API key set. Add one in Settings → Online.")
          return@launch
        }
        callbacks.onStatus("Connecting to OpenAI…")
        val ephemeral = OpenAiRealtime.mintEphemeral(key, floresToOpenAiLang(tgtFlores))
        if (!running.get()) { callbacks.onFinished(null); return@launch } // stopped while minting

        val client = OkHttpClient.Builder()
          .pingInterval(20, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
          .build()
        val request = Request.Builder()
          .url("${OpenAiRealtime.WS_URL}?model=${OpenAiRealtime.MODEL}")
          .header("Authorization", "Bearer $ephemeral")
          .header("OpenAI-Safety-Identifier", OpenAiRealtime.SAFETY_ID)
          .build()
        webSocket = client.newWebSocket(request, listener(callbacks))

        // Audio capture + playback start once the socket is open.
        launch(Dispatchers.IO) { playbackLoop() }
        launch(Dispatchers.IO) { captureLoop(callbacks) }
        launch(Dispatchers.IO) { idleWatchdog(callbacks) }

        finished.await()
        callbacks.onFinished(failure)
      } catch (e: Throwable) {
        callbacks.onFinished(e.message ?: e.toString())
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

  private fun listener(cb: TranslatorCallbacks) = object : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
      val lang = floresToOpenAiLang(tgtFlores)
      // Enabling input transcription is what makes the model emit source-language
      // (input_transcript) deltas; without it only the translation comes back.
      ws.send(
        """{"type":"session.update","session":{"audio":{""" +
          """"input":{"transcription":{"model":"$INPUT_TRANSCRIBE_MODEL"}},""" +
          """"output":{"language":"$lang"}}}}""",
      )
      cb.onStatus("Listening (online → ${labelOf(tgtFlores)})…")
      if (!opened.isCompleted) opened.complete(true)
    }

    override fun onMessage(ws: WebSocket, text: String) {
      handleEvent(text, cb)
    }

    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
      finalizeTurn(cb)
      if (!finished.isCompleted) finished.complete(Unit)
    }

    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
      if (running.get()) failure = "Online error: ${t.message}"
      if (!opened.isCompleted) opened.complete(false)
      if (!finished.isCompleted) finished.complete(Unit)
    }
  }

  private fun handleEvent(text: String, cb: TranslatorCallbacks) {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
    when {
      type == "error" -> {
        val msg = (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        cb.onStatus("Online error: ${msg ?: type}")
      }
      type.contains("input") && type.contains("transcript") && type.endsWith("delta") ->
        onSourceDelta(delta(obj), cb)
      type.contains("output") && type.contains("transcript") && type.endsWith("delta") ->
        onTargetDelta(delta(obj), cb)
      type.contains("output") && type.contains("audio") && type.endsWith("delta") ->
        delta(obj).takeIf { it.isNotEmpty() }?.let { enqueueAudio(it) }
      type.contains("transcript") && type.endsWith("done") -> finalizeTurn(cb)
    }
  }

  private fun delta(obj: JsonObject): String =
    obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun onSourceDelta(d: String, cb: TranslatorCallbacks) {
    if (d.isEmpty()) return
    synchronized(lock) {
      lastDeltaAt = nowMs()
      val id = ensureTurn(cb)
      srcAcc.append(d)
      cb.onTurnUpdate(id, transcript = srcAcc.toString())
    }
  }

  private fun onTargetDelta(d: String, cb: TranslatorCallbacks) {
    if (d.isEmpty()) return
    synchronized(lock) {
      lastDeltaAt = nowMs()
      val id = ensureTurn(cb)
      tgtAcc.append(d)
      cb.onTurnUpdate(id, translation = tgtAcc.toString())
    }
  }

  // Caller holds [lock].
  private fun ensureTurn(cb: TranslatorCallbacks): Long {
    currentTurnId?.let { return it }
    val id = nextId.getAndIncrement()
    currentTurnId = id
    srcAcc = StringBuilder()
    tgtAcc = StringBuilder()
    cb.onTurnStart(LiveTurn(id, transcript = "", srcLang = srcFlores, tgtLang = tgtFlores, detected = ""))
    return id
  }

  private fun finalizeTurn(cb: TranslatorCallbacks) {
    synchronized(lock) {
      val id = currentTurnId ?: return
      cb.onTurnUpdate(id, transcript = srcAcc.toString(), translation = tgtAcc.toString())
      currentTurnId = null
    }
    cb.onPersist()
  }

  // No `*.done` event for a while after the last delta → close the open turn.
  private suspend fun idleWatchdog(cb: TranslatorCallbacks) {
    while (running.get()) {
      kotlinx.coroutines.delay(IDLE_POLL_MS)
      val open = synchronized(lock) { currentTurnId != null }
      if (open && nowMs() - lastDeltaAt > IDLE_GAP_MS) finalizeTurn(cb)
    }
  }

  // --- Audio in (24 kHz PCM16) ---

  private suspend fun captureLoop(cb: TranslatorCallbacks) {
    if (!opened.await()) return // socket never opened
    if (!running.get()) return
    val native24k = supportsCaptureRate(SAMPLE_RATE_24K)
    val sampleRate = if (native24k) SAMPLE_RATE_24K else SAMPLE_RATE_16K
    try {
      rawChunkCaptureLoop(running, sampleRate, MediaRecorder.AudioSource.VOICE_COMMUNICATION) { chunk ->
        val pcm24 = if (native24k) chunk else linearResample16to24(chunk)
        val ws = webSocket ?: return@rawChunkCaptureLoop
        // Drop audio rather than build an unbounded send queue on a stalled link.
        if (ws.queueSize() > MAX_WS_QUEUE_BYTES) return@rawChunkCaptureLoop
        val b64 = Base64.encodeToString(pcm24, Base64.NO_WRAP)
        ws.send("""{"type":"session.input_audio_buffer.append","audio":"$b64"}""")
      }
    } catch (e: Throwable) {
      if (running.get()) cb.onStatus("Mic error: ${e.message}")
    }
  }

  // --- Audio out (24 kHz PCM16) ---

  private suspend fun playbackLoop() {
    val track = buildAudioTrack()
    track.play()
    try {
      for (pcm in audioChannel) {
        if (settings.autoSpeak) track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
      }
    } catch (_: Throwable) {
      // Channel closed on teardown; fall through to release.
    } finally {
      runCatching { track.stop() }
      runCatching { track.release() }
    }
  }

  private fun enqueueAudio(b64: String) {
    val pcm = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
    audioChannel.trySend(pcm)
  }

  private fun buildAudioTrack(): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(
      SAMPLE_RATE_24K,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(SAMPLE_RATE_24K)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE_24K)) // ~0.5 s of headroom
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }

  private fun teardown() {
    runCatching { audioChannel.close() }
    runCatching { webSocket?.cancel() }
    webSocket = null
  }

  private fun labelOf(flores: String): String = floresToOpenAiLang(flores).uppercase()

  private fun nowMs(): Long = System.nanoTime() / 1_000_000

  private companion object {
    const val INPUT_TRANSCRIBE_MODEL = "gpt-realtime-whisper"
    const val MAX_WS_QUEUE_BYTES = 256 * 1024L
    const val IDLE_POLL_MS = 300L
    const val IDLE_GAP_MS = 1200L
  }
}
