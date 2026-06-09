package app.rly3h.yakumo.ui.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.rly3h.yakumo.R
import app.rly3h.yakumo.data.LoggedUtterance
import app.rly3h.yakumo.data.Models
import app.rly3h.yakumo.data.SessionLog
import app.rly3h.yakumo.data.SessionStore
import app.rly3h.yakumo.data.Settings
import app.rly3h.yakumo.translate.LiveTurn
import app.rly3h.yakumo.translate.OfflineTranslator
import app.rly3h.yakumo.translate.OnlineTranslator
import app.rly3h.yakumo.translate.SpeechTranslator
import app.rly3h.yakumo.translate.TranslatorCallbacks

@Composable
fun NewSessionScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = remember { Settings(context) }

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

  // OS TTS reads the translation aloud on the offline path (online plays the
  // model's translated audio directly, so it never calls onSpeak).
  val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
  DisposableEffect(Unit) {
    val engine = TextToSpeech(context) { }
    ttsRef.value = engine
    onDispose { engine.stop(); engine.shutdown() }
  }

  val turns = remember { mutableStateListOf<LiveTurn>() } // chronological: newest last
  var status by remember { mutableStateOf("Tap the mic and speak (EN or JA).") }
  var partial by remember { mutableStateOf("") } // live transcript (streaming mode)
  var recording by remember { mutableStateOf(false) }
  var autoSpeak by remember { mutableStateOf(settings.autoSpeak) }
  var online by remember { mutableStateOf(settings.onlineEnabled) }
  // The user's own language is fixed (changed in Settings); the partner's language
  // is chosen here, null meaning auto-detect. They collapse to the translator's
  // LanguagePair at Start (see Conversation.toPair).
  val mine = remember { settings.conversation().mine }
  var partner by remember { mutableStateOf(settings.conversation().partner) }
  val listState = rememberLazyListState()

  val networkUp by rememberNetworkAvailable()
  val canGoOnline = networkUp && settings.hasApiKey()
  // Online needs a key + network; once either drops, fall back to offline.
  LaunchedEffect(canGoOnline) { if (!canGoOnline) online = false }

  // Active engine for the running session; null when idle. Held so stop() reaches it.
  val translatorRef = remember { mutableStateOf<SpeechTranslator?>(null) }

  // Keep the newest content in view as turns (and the live partial) stream in.
  LaunchedEffect(turns.size, partial) {
    val last = turns.size - 1 + if (partial.isNotBlank()) 1 else 0
    if (last >= 0) listState.animateScrollToItem(last)
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

  // Bridges an engine's events onto this screen's state. Compose snapshot state is
  // thread-safe to mutate, so engines may invoke these from background threads.
  val callbacks = remember {
    object : TranslatorCallbacks {
      override fun onTurnStart(turn: LiveTurn) { turns.add(turn) }

      override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
        val idx = turns.indexOfFirst { it.id == id }
        if (idx < 0) return
        turns[idx] = turns[idx].copy(
          transcript = transcript ?: turns[idx].transcript,
          translation = translation ?: turns[idx].translation,
        )
      }

      override fun onPartial(text: String) { partial = text }
      override fun onStatus(text: String) { status = text }
      override fun onSpeak(text: String, tgtFlores: String) { speak(text, tgtFlores) }
      override fun onPersist() { persist() }

      override fun onFinished(error: String?) {
        recording = false
        partial = ""
        translatorRef.value = null
        status = error?.let { "Error: $it" }
          ?: "Stopped. (${turns.size} turn${if (turns.size == 1) "" else "s"})"
      }
    }
  }

  fun start() {
    if (!hasPermission) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    // Snapshot the engine choice for the whole session — switching mid-session
    // isn't supported (mirrors the streamingAsr capture-once rule).
    val useOnline = online && canGoOnline
    val streamingAsr = settings.streamingAsr
    if (!useOnline && streamingAsr && !Models.isPresent(context, "asr_stream")) {
      status = "Streaming model missing — download it in Settings → Experimental."
      return
    }
    // Direction is always auto-detected per utterance; the partner choice only
    // resolves which language sits opposite the user.
    val pair = Conversation(mine, partner).toPair()
    val translator: SpeechTranslator =
      if (useOnline) OnlineTranslator(context, settings, pair, InputMode.AUTO)
      else OfflineTranslator(context, settings, pair, InputMode.AUTO, streamingAsr)
    translatorRef.value = translator
    recording = true
    status = when {
      useOnline -> "Connecting…"
      streamingAsr -> "Listening (streaming EN)…"
      else -> "Listening… speak, then tap Stop."
    }
    translator.start(scope, callbacks)
  }

  fun stop() {
    translatorRef.value?.stop()
    status = "Finishing…"
  }

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Top bar: the partner-language control (the only language you pick here) on
    // the left; engine + speak toggles on the right. Your own language is fixed in
    // Settings and shown only as a quiet caption below.
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PartnerPicker(
        mine = mine,
        partner = partner,
        enabled = !recording,
        onChange = {
          partner = it
          settings.partnerFlores = it?.flores
        },
      )
      // Online/Offline engine toggle. Enabled only with a key + network, and
      // never mid-session (the engine is captured at Start). The speak toggle lives
      // down by the mic, next to the control it affects.
      FilledTonalIconToggleButton(
        checked = online,
        enabled = canGoOnline && !recording,
        onCheckedChange = {
          online = it
          settings.onlineEnabled = it
        },
      ) {
        Icon(
          painterResource(if (online) R.drawable.ic_cloud else R.drawable.ic_cloud_off),
          contentDescription = if (online) "Online mode" else "Offline mode",
        )
      }
    }

    // Which language is "yours" — fixed; changed in Settings, not mid-conversation.
    Text(
      "You: ${mine.label}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
    )

    // Why the Online toggle is unavailable (only when the user might expect it).
    if (!recording && !canGoOnline) {
      val reason = when {
        !settings.hasApiKey() -> "Add an OpenAI API key in Settings to use Online mode."
        !networkUp -> "Online mode needs an internet connection."
        else -> null
      }
      reason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      }
    }

    // Conversation log: oldest at top, newest at the bottom (auto-scrolled). The
    // live partial transcript rides along as a trailing entry so it never
    // overlaps the controls below.
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      if (turns.isEmpty() && partial.isBlank()) {
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
          if (partial.isNotBlank()) {
            item(key = "partial") { PartialTurn(partial) }
          }
        }
      }
    }

    // Status + the mic/stop control, anchored at the bottom.
    Column(
      Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Mirror the speak toggle's footprint on the left so the mic stays centered.
        Spacer(Modifier.size(48.dp))
        Spacer(Modifier.weight(1f))
        Surface(
          onClick = { if (recording) stop() else start() },
          shape = CircleShape,
          color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(72.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
              contentDescription = if (recording) "Stop" else "Start recording",
              modifier = Modifier.size(32.dp),
              tint = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
        Spacer(Modifier.weight(1f))
        // Speak-aloud toggle, beside the mic it relates to.
        IconToggleButton(
          checked = autoSpeak,
          onCheckedChange = {
            autoSpeak = it
            settings.autoSpeak = it
          },
        ) {
          Icon(
            painterResource(if (autoSpeak) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
            contentDescription = if (autoSpeak) "Speak translations aloud" else "Translations muted",
          )
        }
      }
    }
  }
}

// Tracks whether the device currently has a validated internet connection, used
// to gate the Online toggle. Seeded synchronously, then updated via callbacks.
@Composable
private fun rememberNetworkAvailable(): State<Boolean> {
  val context = LocalContext.current
  val state = remember { mutableStateOf(hasInternet(context)) }
  DisposableEffect(Unit) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { state.value = true }
      override fun onLost(network: Network) { state.value = hasInternet(context) }
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      }
    }
    cm.registerDefaultNetworkCallback(cb)
    onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
  }
  return state
}

private fun hasInternet(context: Context): Boolean {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
  val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// The partner's language: "Auto" (detect it) or pinned to a specific language.
// The only language control on this screen — the user's own side is set in Settings.
@Composable
private fun PartnerPicker(
  mine: LanguageOption,
  partner: LanguageOption?,
  enabled: Boolean,
  onChange: (LanguageOption?) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val label = partner?.let { "Partner: ${it.label}" } ?: "Partner: Auto"
  Box {
    AssistChip(
      onClick = { open = true },
      enabled = enabled,
      label = { Text(label) },
      trailingIcon = {
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
      },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(text = { Text("Auto-detect") }, onClick = { open = false; onChange(null) })
      partnerOptions(mine).forEach { opt ->
        DropdownMenuItem(text = { Text(opt.label) }, onClick = { open = false; onChange(opt) })
      }
    }
  }
}

// A finished (or in-flight) turn. No card background — turns are separated by the
// list spacing and the src→tgt header alone, per the flat look we want.
@Composable
internal fun TurnCard(transcript: String, srcLang: String, tgtLang: String, translation: String?) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
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
      translation ?: "…", // language-neutral while the translation streams in
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

// The streaming recognizer's live, not-yet-finalized transcript, shown at the
// tail of the log in the accent color to set it apart from committed turns.
@Composable
private fun PartialTurn(text: String) {
  Text(
    text,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.primary,
  )
}
