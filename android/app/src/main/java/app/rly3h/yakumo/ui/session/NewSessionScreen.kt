package app.rly3h.yakumo.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.rly3h.yakumo.data.LoggedUtterance
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.SessionLog
import app.rly3h.yakumo.data.SessionStore
import app.rly3h.yakumo.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.asrRecognize
import uniffi.translatecore.asrStreamAccept
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.asrStreamReset
import uniffi.translatecore.translateText

private data class LiveTurn(
  val id: Long,
  val transcript: String,
  val srcLang: String,
  val tgtLang: String,
  val detected: String,
  val translation: String? = null, // null while translating
)

private const val ORT_DYLIB = "libonnxruntime.so"

@Composable
fun NewSessionScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }

  val asrDir = remember { Models.dir(context, "asr") }
  val streamDir = remember { Models.dir(context, "asr_stream") }
  val nllbDir = remember { Models.dir(context, "nllb") }
  // Captured once: switching engines mid-session isn't supported.
  val streamingAsr = remember { settings.streamingAsr }

  val startedAt = remember { System.currentTimeMillis() }
  val sessionId = remember { SessionStore.newId(startedAt) }

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

  // OS TTS reads the translation aloud (Japanese for the EN→JA flow).
  val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
  DisposableEffect(Unit) {
    val engine = TextToSpeech(context) { }
    ttsRef.value = engine
    onDispose { engine.stop(); engine.shutdown() }
  }

  val turns = remember { mutableStateListOf<LiveTurn>() } // chronological: newest last
  var nextId by remember { mutableStateOf(0L) }
  var status by remember { mutableStateOf("Tap the mic and speak (EN or JA).") }
  var partial by remember { mutableStateOf("") } // live transcript (streaming mode)
  var recording by remember { mutableStateOf(false) }
  var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
  val listState = rememberLazyListState()
  // Read from the capture thread, so an AtomicBoolean rather than Compose state.
  val running = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

  // Keep the newest turn in view as it streams in.
  LaunchedEffect(turns.size) {
    if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
  }

  fun persist() {
    val done = turns.filter { it.translation != null } // already chronological
    if (done.isEmpty()) return
    SessionStore.save(
      context,
      SessionLog(
        id = sessionId,
        startedAt = startedAt,
        utterances = done.map {
          LoggedUtterance(it.transcript, it.srcLang, it.tgtLang, it.detected, it.translation!!)
        },
      ),
    )
  }

  fun speak(text: String, tgt: String) {
    if (!settings.autoSpeak) return
    ttsRef.value?.let { engine ->
      val avail = engine.setLanguage(localeFromFlores(tgt))
      if (avail != TextToSpeech.LANG_MISSING_DATA && avail != TextToSpeech.LANG_NOT_SUPPORTED) {
        engine.setSpeechRate(settings.speechRate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "turn")
      }
    }
  }

  // Final transcript -> turn (shown at once) -> translation (patched in) ->
  // spoken. `lang` is the ASR language tag, empty for the English-only streaming
  // recognizer (then the script heuristic picks the direction).
  suspend fun finalizeTurn(transcript: String, lang: String) {
    val src = floresFromAsrLang(lang)
      ?: if (isJapanese(transcript)) "jpn_Jpan" else "eng_Latn"
    val tgt = if (src == "jpn_Jpan") "eng_Latn" else "jpn_Jpan"

    val id = withContext(Dispatchers.Main) {
      val newId = nextId++
      turns.add(LiveTurn(newId, transcript, src, tgt, lang, translation = null))
      newId
    }
    val translation = withContext(Dispatchers.IO) {
      Models.ensure(context, "nllb")
      translateText(nllbDir.absolutePath, transcript, src, tgt, ORT_DYLIB)
    }
    withContext(Dispatchers.Main) {
      val idx = turns.indexOfFirst { it.id == id }
      if (idx >= 0) turns[idx] = turns[idx].copy(translation = translation)
      persist()
    }
    speak(translation, tgt)
  }

  // Offline (SenseVoice) path: one VAD-cut segment -> transcript -> finalize.
  suspend fun processSegment(pcm: ByteArray) {
    val asr = withContext(Dispatchers.IO) {
      Models.ensure(context, "asr")
      asrRecognize(asrDir.absolutePath, pcm, 16000)
    }
    val transcript = asr.text.trim()
    if (transcript.isEmpty()) return // drop non-speech segments
    finalizeTurn(transcript, asr.lang)
  }

  // Capture and processing are decoupled by a channel so recording keeps running
  // while each finished segment is transcribed/translated.
  suspend fun runSegmented() = kotlinx.coroutines.coroutineScope {
    val vad = settings.vadParams() // latest knobs at the start of this session
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try { captureLoop(running, vad) { seg -> channel.trySend(seg) } } finally { channel.close() }
    }
    val consumer = launch(Dispatchers.IO) {
      for (seg in channel) {
        try {
          processSegment(seg)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { status = "Error: ${e.message}" }
        }
      }
    }
    capture.join()
    consumer.join()
  }

  // Streaming (nemotron-en) path: feed raw mic chunks into the online recognizer,
  // show its partial transcript live, and finalize each utterance on its endpoint.
  suspend fun runStreaming() = kotlinx.coroutines.coroutineScope {
    val ep = settings.endpointParams() // latest knobs at the start of this session
    withContext(Dispatchers.IO) {
      asrStreamLoad(streamDir.absolutePath, ep.rule1, ep.rule2, ep.rule3)
      asrStreamReset(streamDir.absolutePath)
    }
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    val capture = launch(Dispatchers.IO) {
      try { streamingCaptureLoop(running) { channel.trySend(it) } } finally { channel.close() }
    }
    val consumer = launch(Dispatchers.IO) {
      var current = ""
      for (chunk in channel) {
        val r = try {
          asrStreamAccept(streamDir.absolutePath, chunk, 16000)
        } catch (e: Throwable) {
          withContext(Dispatchers.Main) { status = "Error: ${e.message}" }
          continue
        }
        current = r.text
        withContext(Dispatchers.Main) { partial = r.text }
        if (r.endpoint) {
          val t = r.text.trim()
          current = ""
          withContext(Dispatchers.Main) { partial = "" }
          if (t.isNotEmpty()) finalizeTurn(t, "")
        }
      }
      // Stopped mid-utterance: flush whatever was decoded so far.
      val tail = current.trim()
      withContext(Dispatchers.Main) { partial = "" }
      if (tail.isNotEmpty()) finalizeTurn(tail, "")
      withContext(Dispatchers.IO) { asrStreamReset(streamDir.absolutePath) }
    }
    capture.join()
    consumer.join()
  }

  fun start() {
    if (!hasPermission) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    if (streamingAsr && !Models.isPresent(context, "asr_stream")) {
      status = "Streaming model missing — download it in Settings → Experimental."
      return
    }
    recording = true
    running.set(true)
    status = if (streamingAsr) "Listening (streaming EN)…" else "Listening… speak, then tap Stop."
    scope.launch {
      if (streamingAsr) runStreaming() else runSegmented()
      recording = false
      partial = ""
      status = "Stopped. (${turns.size} turn${if (turns.size == 1) "" else "s"})"
    }
  }

  fun stop() {
    running.set(false)
    status = "Finishing…"
  }

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Direction header. Detection is per-utterance, so the pair is shown as auto.
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("EN ⇄ JA · auto", style = MaterialTheme.typography.labelLarge)
      FilterChip(
        selected = autoSpeak,
        onClick = {
          autoSpeak = !autoSpeak
          settings.autoSpeak = autoSpeak
        },
        label = { Text(if (autoSpeak) "🔊" else "🔇") },
      )
    }

    // Conversation log: oldest at top, newest at the bottom (auto-scrolled).
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      if (turns.isEmpty()) {
        Text(
          "Tap the mic and start speaking.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.outline,
        )
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(turns, key = { it.id }) { t -> TurnCard(t.transcript, t.srcLang, t.tgtLang, t.translation) }
        }
      }
    }

    // Status + the mic/stop control, anchored at the bottom.
    Column(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (partial.isNotBlank()) {
        Text(
          partial,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      Surface(
        onClick = { if (recording) stop() else start() },
        shape = CircleShape,
        color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            if (recording) "■" else "🎤",
            style = MaterialTheme.typography.headlineSmall,
            color = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
          )
        }
      }
    }
  }
}

@Composable
internal fun TurnCard(transcript: String, srcLang: String, tgtLang: String, translation: String?) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        "${labelForFlores(srcLang)} → ${labelForFlores(tgtLang)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        transcript,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        translation ?: "翻訳中…",
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}
