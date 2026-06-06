package com.example.offlinetranslate.ui.main

import android.net.Uri
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
import com.example.offlinetranslate.theme.OfflineTranslateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    TtsPanel()
  }
}

// Speeds applied at playback time (decoupled from synthesis), per the design.
private val SPEEDS = listOf(1.0f, 1.5f, 2.0f, 2.5f)

@Composable
private fun TtsPanel(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val player = remember { ExoPlayer.Builder(context).build() }

  val modelName = "kokoro-int8-multi-lang-v1_1"
  // Model is delivered as a zip on external storage (adb push for the PoC; a
  // network download in production), then extracted to internal storage. The
  // NDK's raw open() is denied on Android/data on some OEMs, but internal
  // storage (filesDir) is always native-readable.
  val externalZip = remember { File(context.getExternalFilesDir(null), "$modelName.zip") }
  val internalDir = remember { File(context.filesDir, modelName) }
  val outWav = remember { File(context.cacheDir, "tts.wav") }

  var text by remember { mutableStateOf("Hello, this is Kokoro running fully offline on device.") }
  var speed by remember { mutableStateOf(1.0f) }
  var status by remember {
    mutableStateOf(
      when {
        internalDir.exists() -> "Model ready (internal)."
        externalZip.exists() -> "Model zip staged; will extract on first run."
        else -> "Model zip NOT found at ${externalZip.absolutePath}"
      }
    )
  }
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
            // One-time extract external zip -> internal (NDK can't open Android/data on some OEMs).
            if (!internalDir.exists()) {
              unzipTo(externalZip, internalDir)
            }
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

// Extracts a zip into destDir, guarding against path traversal.
private fun unzipTo(zip: File, destDir: File) {
  destDir.mkdirs()
  val destRoot = destDir.canonicalFile
  java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
    var entry = zin.nextEntry
    while (entry != null) {
      val target = File(destDir, entry.name).canonicalFile
      require(target.path.startsWith(destRoot.path)) { "zip entry escapes dest: ${entry.name}" }
      if (entry.isDirectory) {
        target.mkdirs()
      } else {
        target.parentFile?.mkdirs()
        target.outputStream().use { zin.copyTo(it) }
      }
      entry = zin.nextEntry
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  OfflineTranslateTheme { MainScreen(listOf("Rust core wired", "sherpa-onnx 1.13.2")) }
}
