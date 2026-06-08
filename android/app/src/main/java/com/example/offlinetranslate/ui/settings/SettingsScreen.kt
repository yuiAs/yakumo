package com.example.offlinetranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.example.offlinetranslate.data.Models
import com.example.offlinetranslate.data.Settings
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

  var rate by remember { mutableStateOf(settings.speechRate) }
  var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
  var modelStatus by remember { mutableStateOf(modelLine()) }
  var engineStatus by remember { mutableStateOf("not loaded (first use loads them)") }
  var busy by remember { mutableStateOf(false) }

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
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
      enabled = !busy,
      onClick = {
        busy = true
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              for (id in ids) Models.ensure(context, id) { modelStatus = it }
            }
            modelStatus = modelLine()
          } catch (e: Throwable) {
            modelStatus = "Error: ${e.message}"
          } finally {
            busy = false
          }
        }
      },
    ) { Text(if (busy) "Working…" else "Download all models") }

    Button(
      enabled = !busy,
      onClick = {
        busy = true
        engineStatus = "loading…"
        scope.launch {
          try {
            withContext(Dispatchers.IO) {
              engineStatus = "loading asr…"
              Models.ensure(context, "asr") { engineStatus = it }
              asrLoad(Models.dir(context, "asr").absolutePath)
              engineStatus = "loading nllb…"
              Models.ensure(context, "nllb") { engineStatus = it }
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

    SectionTitle("About")
    Text(remember { coreVersion() }, style = MaterialTheme.typography.bodySmall)
    Text(remember { sherpaVersion() }, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium)
}
