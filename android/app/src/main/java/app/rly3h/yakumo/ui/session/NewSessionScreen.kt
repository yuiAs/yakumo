package app.rly3h.yakumo.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.asrRecognize
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
private const val RECORD_MS = 5000L

@Composable
fun NewSessionScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }

  val asrDir = remember { Models.dir(context, "asr") }
  val nllbDir = remember { Models.dir(context, "nllb") }

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

  val turns = remember { mutableStateListOf<LiveTurn>() } // newest first
  var nextId by remember { mutableStateOf(0L) }
  var status by remember { mutableStateOf("Tap record and speak (EN or JA).") }
  var busy by remember { mutableStateOf(false) }

  fun persist() {
    val done = turns.filter { it.translation != null }.reversed() // store oldest first
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

  fun record() {
    if (!hasPermission) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    busy = true
    status = "Recording 5s…"
    scope.launch {
      try {
        val asr = withContext(Dispatchers.IO) {
          val pcm = recordPcm16(RECORD_MS)
          status = "Transcribing…"
          Models.ensure(context, "asr") { status = it }
          asrRecognize(asrDir.absolutePath, pcm, 16000)
        }
        val transcript = asr.text
        val src = floresFromAsrLang(asr.lang)
          ?: if (isJapanese(transcript)) "jpn_Jpan" else "eng_Latn"
        val tgt = if (src == "jpn_Jpan") "eng_Latn" else "jpn_Jpan"

        val id = nextId++
        turns.add(0, LiveTurn(id, transcript, src, tgt, asr.lang, translation = null))

        status = "Translating…"
        val translation = withContext(Dispatchers.IO) {
          Models.ensure(context, "nllb") { status = it }
          translateText(nllbDir.absolutePath, transcript, src, tgt, ORT_DYLIB)
        }
        val idx = turns.indexOfFirst { it.id == id }
        if (idx >= 0) turns[idx] = turns[idx].copy(translation = translation)
        persist()
        speak(translation, tgt)
        status = "Done."
      } catch (e: Throwable) {
        status = "Error: ${e.message}"
      } finally {
        busy = false
      }
    }
  }

  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(
      onClick = { record() },
      enabled = !busy,
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (busy) {
        CircularProgressIndicator(
          modifier = Modifier.padding(end = 8.dp),
          strokeWidth = 2.dp,
        )
      }
      Text(if (busy) "Working…" else "● Record & translate")
    }

    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(status, style = MaterialTheme.typography.bodySmall)
      var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
      FilterChip(
        selected = autoSpeak,
        onClick = {
          autoSpeak = !autoSpeak
          settings.autoSpeak = autoSpeak
        },
        label = { Text(if (autoSpeak) "🔊" else "🔇") },
      )
    }

    if (turns.isEmpty()) {
      Text(
        "No turns yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(turns, key = { it.id }) { t -> TurnCard(t.transcript, t.srcLang, t.tgtLang, t.translation) }
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
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "${labelForFlores(srcLang)}  ${transcript}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "${labelForFlores(tgtLang)}  ${translation ?: "翻訳中…"}",
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}
