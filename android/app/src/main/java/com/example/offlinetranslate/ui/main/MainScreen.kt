package com.example.offlinetranslate.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation3.runtime.NavKey
import com.example.offlinetranslate.data.DefaultDataRepository
import com.example.offlinetranslate.data.Models
import com.example.offlinetranslate.theme.OfflineTranslateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import uniffi.translatecore.asrLoad
import uniffi.translatecore.asrRecognize
import uniffi.translatecore.translateLoad
import uniffi.translatecore.translateText
import uniffi.translatecore.ttsLoad
import uniffi.translatecore.ttsSynthesize

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  when (state) {
    MainScreenUiState.Loading -> {
      // Blank
    }
    is MainScreenUiState.Success -> {
      MainScreen(data = (state as MainScreenUiState.Success).data, modifier = modifier)
    }
    is MainScreenUiState.Error -> {
      Text("Error loading data: ${(state as MainScreenUiState.Error).throwable.message}")
    }
  }
}

@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
  Column(
    modifier
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    data.forEach { Text(text = it) }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SetupPanel()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SessionPanel()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("— individual debug panels —", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    AsrPanel()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    TranslatePanel()
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    TtsPanel()
  }
}

private data class Utterance(
  val transcript: String,
  val srcLang: String,
  val translation: String,
  val tgtLang: String,
  val detected: String = "",
)

// FLORES code chosen by a cheap script heuristic (kana/kanji -> Japanese source).
private fun isJapanese(text: String): Boolean =
  text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

// Map SenseVoice's language tag ("<|en|>", "<|ja|>", ...) to a FLORES source
// code for the EN<->JA pair. Null for other/empty tags -> caller falls back to
// the script heuristic.
private fun floresFromAsrLang(lang: String): String? = when {
  lang.contains("ja") -> "jpn_Jpan"
  lang.contains("en") -> "eng_Latn"
  else -> null
}

// End-to-end debug flow: record -> ASR (Transcript) -> NLLB (Translate) -> show both.
@Composable
private fun SessionPanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val asrDir = remember { Models.dir(context, "asr") }
  val nllbDir = remember { Models.dir(context, "nllb") }
  val ortDylib = "libonnxruntime.so"

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

  val log = remember { mutableStateListOf<Utterance>() }
  var status by remember { mutableStateOf("Ready. (record EN or JA — direction auto)") }
  var busy by remember { mutableStateOf(false) }

  Text("Session — record → transcript → translate", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

  Button(
    enabled = !busy,
    onClick = {
      if (!hasPermission) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return@Button
      }
      busy = true
      status = "Recording 5s…"
      scope.launch {
        try {
          val u = withContext(Dispatchers.IO) {
            val pcm = recordPcm16(5000)
            status = "Transcribing…"
            Models.ensure(context, "asr") { status = it }
            val asr = asrRecognize(asrDir.absolutePath, pcm, 16000)
            val transcript = asr.text
            // Prefer SenseVoice's detected language; fall back to the heuristic.
            val src = floresFromAsrLang(asr.lang)
              ?: if (isJapanese(transcript)) "jpn_Jpan" else "eng_Latn"
            val tgt = if (src == "jpn_Jpan") "eng_Latn" else "jpn_Jpan"
            status = "Translating $src → $tgt…"
            Models.ensure(context, "nllb") { status = it }
            val translation = translateText(nllbDir.absolutePath, transcript, src, tgt, ortDylib)
            Utterance(transcript, src, translation, tgt, asr.lang)
          }
          log.add(0, u)
          status = "Done. (${log.size} utterance${if (log.size == 1) "" else "s"})"
        } catch (e: Throwable) {
          status = "Error: ${e.message}"
        } finally {
          busy = false
        }
      }
    },
  ) { Text(if (busy) "Working…" else "Record & translate") }

  Text(status)

  // Transcript / Translate 併記ログ(新しい発話が上)
  log.forEach { u ->
    Column(
      Modifier.fillMaxWidth().padding(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        "Transcript [${u.srcLang}${if (u.detected.isNotBlank()) " · asr:${u.detected}" else ""}]: ${u.transcript}",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
      )
      Text(
        "Translate  [${u.tgtLang}]: ${u.translation}",
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
      )
      HorizontalDivider(Modifier.padding(top = 4.dp))
    }
  }
}

@Composable
private fun TranslatePanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val nllbDir = remember { Models.dir(context, "nllb") }
  // onnxruntime is already loaded into the process (translatecore.so -> sherpa ->
  // onnxruntime). The soname lets ort dlopen the already-resident lib even with
  // extractNativeLibs=false (libs mmap'd from the APK, no on-disk path).
  val ortDylib = "libonnxruntime.so"

  var text by remember { mutableStateOf("Good morning, everyone. It is such a pleasure to see you all.") }
  var result by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("Ready.") }
  var busy by remember { mutableStateOf(false) }

  fun run(src: String, tgt: String) {
    busy = true
    result = ""
    status = "Translating $src → $tgt…"
    scope.launch {
      try {
        var ms = 0L
        val r = withContext(Dispatchers.IO) {
          Models.ensure(context, "nllb") { status = it }
          val start = System.nanoTime()
          val out = translateText(nllbDir.absolutePath, text, src, tgt, ortDylib)
          ms = (System.nanoTime() - start) / 1_000_000
          out
        }
        result = r
        status = "Done ($src → $tgt) in ${ms} ms."
      } catch (e: Throwable) {
        status = "Error: ${e.message}"
      } finally {
        busy = false
      }
    }
  }

  Text("Translation (NLLB-200)", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
  OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    label = { Text("Text") },
    modifier = Modifier.fillMaxWidth(),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(enabled = !busy, onClick = { run("eng_Latn", "jpn_Jpan") }) { Text("EN → JA") }
    Button(enabled = !busy, onClick = { run("jpn_Jpan", "eng_Latn") }) { Text("JA → EN") }
  }
  Text(status)
  if (result.isNotEmpty()) {
    Text("Translation: $result", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
  }
}

// Speeds applied at playback time (decoupled from synthesis), per the design.
private val SPEEDS = listOf(1.0f, 1.5f, 2.0f, 2.5f)

@Composable
private fun TtsPanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val player = remember { ExoPlayer.Builder(context).build() }

  val internalDir = remember { Models.dir(context, "tts") }
  val outWav = remember { File(context.cacheDir, "tts.wav") }

  var text by remember { mutableStateOf("Hello, this is Kokoro running fully offline on device.") }
  var speed by remember { mutableStateOf(1.0f) }
  var status by remember { mutableStateOf("Ready.") }
  var busy by remember { mutableStateOf(false) }

  Text("TTS (Kokoro) demo", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

  OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    label = { Text("Text to speak") },
    modifier = Modifier.fillMaxWidth(),
  )

  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SPEEDS.forEach { s ->
      FilterChip(
        selected = speed == s,
        onClick = {
          speed = s
          player.playbackParameters = PlaybackParameters(s)
        },
        label = { Text("${s}x") },
      )
    }
  }

  Button(
    enabled = !busy,
    onClick = {
      busy = true
      status = "Synthesizing…"
      scope.launch {
        try {
          // Model load + synthesis are heavy: keep them off the main thread.
          val result = withContext(Dispatchers.IO) {
            Models.ensure(context, "tts") { status = it }
            ttsSynthesize(internalDir.absolutePath, text, /* sid = */ 0, /* speed = */ 1.0f, outWav.absolutePath)
          }
          val seconds = result.numSamples.toFloat() / result.sampleRate
          status = "OK: ${result.sampleRate} Hz, %.2fs audio · playing at ${speed}x".format(seconds)
          player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(result.wavPath))))
          player.playbackParameters = PlaybackParameters(speed)
          player.prepare()
          player.play()
        } catch (e: Throwable) {
          status = "Error: ${e.message}"
        } finally {
          busy = false
        }
      }
    },
  ) { Text(if (busy) "Working…" else "Speak") }

  Text(status)
}

@Composable
private fun AsrPanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val internalDir = remember { Models.dir(context, "asr") }

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasPermission = granted
    }
  LaunchedEffect(Unit) {
    if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
  }

  var transcript by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("Ready.") }
  var busy by remember { mutableStateOf(false) }

  Text("ASR (SenseVoice) demo", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
  Text("Records 5s, auto-detects language, transcribes.")

  Button(
    enabled = !busy,
    onClick = {
      if (!hasPermission) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return@Button
      }
      busy = true
      transcript = ""
      status = "Recording 5s…"
      scope.launch {
        try {
          val asr = withContext(Dispatchers.IO) {
            val pcm = recordPcm16(durationMs = 5000)
            status = "Transcribing…"
            Models.ensure(context, "asr") { status = it }
            asrRecognize(internalDir.absolutePath, pcm, 16000)
          }
          transcript = asr.text.ifBlank { "(no speech detected)" }
          status = if (asr.lang.isNotBlank()) "Done. (detected ${asr.lang})" else "Done."
        } catch (e: Throwable) {
          status = "Error: ${e.message}"
        } finally {
          busy = false
        }
      }
    },
  ) { Text(if (busy) "Working…" else "Record & transcribe") }

  Text(status)
  if (transcript.isNotEmpty()) {
    Text("Transcript: $transcript", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
  }
}

// Records mono 16 kHz signed-16-bit PCM from the mic and returns it as bytes.
private fun recordPcm16(durationMs: Long): ByteArray {
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

// First-run setup: download all models defined in assets/models.json.
@Composable
private fun SetupPanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val ids = remember { Models.manifest(context).models.map { it.id } }
  val ortDylib = "libonnxruntime.so"
  var status by remember {
    mutableStateOf(ids.joinToString("\n") { "$it: ${if (Models.isPresent(context, it)) "ready" else "missing"}" })
  }
  var busy by remember { mutableStateOf(false) }
  // One-time warm-up state: residency means engines load once and stay in RAM.
  var enginesLoaded by remember { mutableStateOf(false) }
  var engineStatus by remember { mutableStateOf("engines: not loaded (first use loads them)") }

  Text("Setup — download models", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
  Button(
    enabled = !busy,
    onClick = {
      busy = true
      scope.launch {
        try {
          withContext(Dispatchers.IO) {
            for (id in ids) Models.ensure(context, id) { status = it }
          }
          status = ids.joinToString("\n") { "$it: ${if (Models.isPresent(context, it)) "ready" else "missing"}" }
        } catch (e: Throwable) {
          status = "Error: ${e.message}"
        } finally {
          busy = false
        }
      }
    },
  ) { Text(if (busy) "Downloading…" else "Download all models") }
  Text(status)

  // Warm-up: load ASR / NLLB / TTS into resident engines up front so the first
  // record/translate/speak doesn't pay the ~850 MB load cost inline.
  Button(
    enabled = !busy,
    onClick = {
      busy = true
      engineStatus = "engines: loading…"
      scope.launch {
        try {
          withContext(Dispatchers.IO) {
            engineStatus = "engines: loading asr…"
            Models.ensure(context, "asr") { engineStatus = it }
            asrLoad(Models.dir(context, "asr").absolutePath)
            engineStatus = "engines: loading nllb…"
            Models.ensure(context, "nllb") { engineStatus = it }
            translateLoad(Models.dir(context, "nllb").absolutePath, ortDylib)
            engineStatus = "engines: loading tts…"
            Models.ensure(context, "tts") { engineStatus = it }
            ttsLoad(Models.dir(context, "tts").absolutePath)
          }
          enginesLoaded = true
          engineStatus = "engines: loaded (asr, nllb, tts) — resident"
        } catch (e: Throwable) {
          engineStatus = "engines error: ${e.message}"
        } finally {
          busy = false
        }
      }
    },
  ) { Text(if (enginesLoaded) "Reload engines" else "Load engines into memory") }
  Text(engineStatus)
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  OfflineTranslateTheme { MainScreen(listOf("Rust core wired", "sherpa-onnx 1.13.2")) }
}
