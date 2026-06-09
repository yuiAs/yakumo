package app.rly3h.yakumo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Row
import app.rly3h.yakumo.BuildConfig
import app.rly3h.yakumo.translate.OpenAiRealtime
import app.rly3h.yakumo.data.ModelCancelled
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.ui.session.EndpointBounds
import app.rly3h.yakumo.ui.session.LANGUAGES
import app.rly3h.yakumo.ui.session.VadBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.asrLoad
import uniffi.translatecore.coreVersion
import uniffi.translatecore.sherpaVersion
import uniffi.translatecore.translateLoad

private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private const val ORT_DYLIB = "libonnxruntime.so"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }
  val ids = remember { Models.manifest(context).models.map { it.id } }

  fun modelLine() = ids.joinToString("\n") {
    "$it: ${if (Models.isPresent(context, it)) "ready" else "missing"}"
  }

  var myLang by remember { mutableStateOf(settings.myLangFlores) }
  var rate by remember { mutableStateOf(settings.speechRate) }
  var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
  var streamingAsr by remember { mutableStateOf(settings.streamingAsr) }
  var modelStatus by remember { mutableStateOf(modelLine()) }
  var engineStatus by remember { mutableStateOf("not loaded (first use loads them)") }
  var busy by remember { mutableStateOf(false) }

  // Download overlay state. The cancel flag is read from the IO thread, so it is
  // an AtomicBoolean rather than Compose state.
  var downloading by remember { mutableStateOf(false) }
  var dlProgress by remember { mutableStateOf("") }
  // null => indeterminate bar (size unknown / between files); else 0..1.
  var dlFraction by remember { mutableStateOf<Float?>(null) }
  val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

  var epRule1 by remember { mutableStateOf(settings.endpointRule1) }
  var epRule2 by remember { mutableStateOf(settings.endpointRule2) }
  var epRule3 by remember { mutableStateOf(settings.endpointRule3) }

  var vadThresh by remember { mutableStateOf(settings.vadThreshold) }
  var vadSilence by remember { mutableStateOf(settings.vadMinSilenceMs.toFloat()) }
  var vadMinSpeech by remember { mutableStateOf(settings.vadMinSpeechMs.toFloat()) }
  var vadMaxSpeech by remember { mutableStateOf(settings.vadMaxSpeechMs.toFloat()) }

  // Online (OpenAI) — the key field is never pre-filled with the stored secret.
  var apiKeyInput by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }
  var onlineStatus by remember { mutableStateOf(if (settings.hasApiKey()) "Key saved." else "No key set.") }

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionTitle("Your language")
    Text(
      "The language you speak. The other person's language is chosen on the New " +
        "Session screen (auto-detected by default).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      LANGUAGES.forEach { opt ->
        FilterChip(
          selected = myLang == opt.flores,
          onClick = {
            myLang = opt.flores
            settings.myLangFlores = opt.flores
          },
          label = { Text(opt.label) },
        )
      }
    }

    HorizontalDivider()

    SectionTitle("Playback")
    Text("Speech rate", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RATES.forEach { r ->
        FilterChip(
          selected = rate == r,
          onClick = {
            rate = r
            settings.speechRate = r
          },
          label = { Text("${r}x") },
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Speak translations aloud", style = MaterialTheme.typography.bodyMedium)
      Switch(
        checked = autoSpeak,
        onCheckedChange = {
          autoSpeak = it
          settings.autoSpeak = it
        },
      )
    }

    HorizontalDivider()

    SectionTitle("Models")
    Text(modelStatus, style = MaterialTheme.typography.bodySmall)
    Button(
      enabled = !busy && !downloading,
      onClick = {
        downloading = true
        cancelFlag.set(false)
        dlProgress = "Starting…"
        dlFraction = null
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              // The experimental streaming model is large and opt-in; it has its
              // own button below rather than riding on "download all".
              for (id in ids.filter { it != "asr_stream" }) {
                Models.ensure(
                  context,
                  id,
                  onProgress = { dlProgress = it },
                  onFraction = { dlFraction = it },
                  cancel = { cancelFlag.get() },
                )
              }
            }
            modelStatus = modelLine()
          } catch (c: ModelCancelled) {
            modelStatus = "Download cancelled"
          } catch (e: Throwable) {
            modelStatus = "Error: ${e.message}"
          } finally {
            downloading = false
          }
        }
      },
    ) { Text("Download all models") }

    Button(
      enabled = !busy && !downloading,
      onClick = {
        busy = true
        engineStatus = "loading…"
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              engineStatus = "loading asr…"
              Models.ensure(context, "asr", onProgress = { engineStatus = it })
              asrLoad(Models.dir(context, "asr").absolutePath)
              engineStatus = "loading nllb…"
              Models.ensure(context, "nllb", onProgress = { engineStatus = it })
              translateLoad(Models.dir(context, "nllb").absolutePath, ORT_DYLIB)
            }
            engineStatus = "loaded (asr, nllb) — resident"
          } catch (e: Throwable) {
            engineStatus = "error: ${e.message}"
          } finally {
            busy = false
          }
        }
      },
    ) { Text("Load models into memory") }
    Text(engineStatus, style = MaterialTheme.typography.bodySmall)

    HorizontalDivider()

    SectionTitle("Voice detection")
    Text(
      "Silero VAD splits speech into turns while recording. Robust to background " +
        "noise. Applies to the next session; changing these reloads the detector.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    VadSlider("Silence to split a turn", vadSilence, VadBounds.minSilenceMs, { "${it.toInt()} ms" }, { vadSilence = it }) {
      settings.vadMinSilenceMs = vadSilence.toInt()
    }
    VadSlider("Speech sensitivity (lower = more sensitive)", vadThresh, VadBounds.threshold, { "%.2f".format(it) }, { vadThresh = it }) {
      settings.vadThreshold = vadThresh
    }
    VadSlider("Min speech length", vadMinSpeech, VadBounds.minSpeechMs, { "${it.toInt()} ms" }, { vadMinSpeech = it }) {
      settings.vadMinSpeechMs = vadMinSpeech.toInt()
    }
    VadSlider("Max segment length", vadMaxSpeech, VadBounds.maxSpeechMs, { "${(it / 1000).toInt()} s" }, { vadMaxSpeech = it }) {
      settings.vadMaxSpeechMs = vadMaxSpeech.toInt()
    }
    TextButton(onClick = {
      settings.resetVad()
      vadThresh = settings.vadThreshold
      vadSilence = settings.vadMinSilenceMs.toFloat()
      vadMinSpeech = settings.vadMinSpeechMs.toFloat()
      vadMaxSpeech = settings.vadMaxSpeechMs.toFloat()
    }) { Text("Reset to defaults") }

    HorizontalDivider()

    SectionTitle("Experimental")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Streaming English ASR (nemotron)", style = MaterialTheme.typography.bodyMedium)
      Switch(
        checked = streamingAsr,
        onCheckedChange = {
          streamingAsr = it
          settings.streamingAsr = it
        },
      )
    }
    Text(
      "Live, low-latency transcripts for the EN→JA flow. English only — leave off for Japanese input. Needs the streaming model below.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    Button(
      enabled = !busy && !downloading,
      onClick = {
        downloading = true
        cancelFlag.set(false)
        dlProgress = "Starting…"
        dlFraction = null
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              Models.ensure(
                context,
                "asr_stream",
                onProgress = { dlProgress = it },
                onFraction = { dlFraction = it },
                cancel = { cancelFlag.get() },
              )
            }
            modelStatus = modelLine()
          } catch (c: ModelCancelled) {
            modelStatus = "Download cancelled"
          } catch (e: Throwable) {
            modelStatus = "Error: ${e.message}"
          } finally {
            downloading = false
          }
        }
      },
    ) { Text("Download streaming model (~464 MB)") }

    Text(
      "End of turn — how the streaming recognizer splits utterances. Applies to the next session; changing these reloads the model.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    VadSlider("Silence to end a turn", epRule2, EndpointBounds.rule2, { "%.1f s".format(it) }, { epRule2 = it }) {
      settings.endpointRule2 = epRule2
    }
    VadSlider("Silence before speech", epRule1, EndpointBounds.rule1, { "%.1f s".format(it) }, { epRule1 = it }) {
      settings.endpointRule1 = epRule1
    }
    VadSlider("Max utterance length", epRule3, EndpointBounds.rule3, { "${it.toInt()} s" }, { epRule3 = it }) {
      settings.endpointRule3 = epRule3
    }
    TextButton(onClick = {
      settings.resetEndpoint()
      epRule1 = settings.endpointRule1
      epRule2 = settings.endpointRule2
      epRule3 = settings.endpointRule3
    }) { Text("Reset to defaults") }

    HorizontalDivider()

    SectionTitle("Online (OpenAI)")
    Text(
      "Optional. When online, you can switch to OpenAI Realtime (gpt-realtime-translate) " +
        "for low-latency speech-to-speech. Audio is sent to OpenAI and billed to your key. " +
        "Offline stays the default — toggle Online next to the mic. The key is encrypted on-device.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    OutlinedTextField(
      value = apiKeyInput,
      onValueChange = { apiKeyInput = it },
      label = { Text("OpenAI API key") },
      singleLine = true,
      visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "Hide" else "Show") }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        enabled = apiKeyInput.isNotBlank() && !busy,
        onClick = {
          settings.setApiKey(context, apiKeyInput.trim())
          apiKeyInput = ""
          keyVisible = false
          onlineStatus = "Key saved."
        },
      ) { Text("Save key") }
      TextButton(
        enabled = !busy,
        onClick = {
          settings.clearApiKey()
          onlineStatus = "Key cleared."
        },
      ) { Text("Clear key") }
      TextButton(
        enabled = settings.hasApiKey() && !busy,
        onClick = {
          busy = true
          onlineStatus = "Testing…"
          scope.launch {
            onlineStatus = try {
              withContext(Dispatchers.IO) {
                val key = settings.apiKey(context) ?: error("Key could not be read")
                OpenAiRealtime.mintEphemeral(key) // succeeds = key + network OK
              }
              "Connection OK."
            } catch (e: Throwable) {
              "Test failed: ${e.message}"
            } finally {
              busy = false
            }
          }
        },
      ) { Text("Test connection") }
    }
    Text(onlineStatus, style = MaterialTheme.typography.bodySmall)

    HorizontalDivider()

    SectionTitle("About")
    // Tight inner spacing keeps the version block together; the parent Column's
    // 12.dp gap would otherwise scatter these one-line entries.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        remember {
          "やくも v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH}) / " +
            "${coreVersion()} / ${sherpaVersion()}"
        },
        style = MaterialTheme.typography.bodySmall,
      )
      remember { Models.manifest(context).models }.forEach { m ->
        Text("${m.id}: ${m.dir}", style = MaterialTheme.typography.bodySmall)
      }
    }
  }

  if (downloading) {
    AlertDialog(
      onDismissRequest = {}, // require an explicit Cancel; ignore outside taps
      title = { Text("Downloading models") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          val frac = dlFraction
          if (frac == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
          }
          Text(dlProgress, style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {
        TextButton(onClick = { cancelFlag.set(true) }) { Text("Cancel") }
      },
      properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    )
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium)
}

// Label + current value + a bounded slider. `onChange` updates UI state live;
// `onCommit` persists once the drag finishes (avoids writing on every tick).
@Composable
private fun VadSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  format: (Float) -> String,
  onChange: (Float) -> Unit,
  onCommit: () -> Unit,
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onCommit, valueRange = range)
  }
}
