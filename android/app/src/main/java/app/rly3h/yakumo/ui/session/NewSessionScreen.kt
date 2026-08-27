package app.rly3h.yakumo.ui.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rly3h.yakumo.R
import app.rly3h.yakumo.data.OnlineProvider
import app.rly3h.yakumo.data.Settings

@Composable
fun NewSessionScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val settings = remember { Settings(context) }
  // Scoped to the Activity, not to this nav entry: the session (and its running
  // engine) has to survive a trip through the drawer into Settings and back.
  val activity = LocalActivity.current as ComponentActivity
  val vm: NewSessionViewModel = viewModel(viewModelStoreOwner = activity)

  // Language settings can change while the user is away on the Settings screen.
  LaunchedEffect(Unit) { vm.refresh() }

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

  val listState = rememberLazyListState()

  val networkUp by rememberNetworkAvailable()
  // Online needs a key for the *selected* provider plus a network link.
  val canGoOnline = networkUp && settings.hasApiKey(settings.onlineProvider)
  // Online needs a key + network; once either drops, fall back to offline —
  // without persisting, so the saved preference survives a dead spot.
  LaunchedEffect(canGoOnline) { if (!canGoOnline) vm.forceOffline() }

  // Keep the newest content in view as turns (and the live partial) stream in.
  LaunchedEffect(vm.turns.size, vm.partial) {
    val last = vm.turns.size - 1 + if (vm.partial.isNotBlank()) 1 else 0
    if (last >= 0) listState.animateScrollToItem(last)
  }

  fun start() {
    if (!hasPermission) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    vm.start(canGoOnline)
  }

  Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Top bar: the partner-language control (the only language you pick here) on
    // the left; new-session + engine toggles on the right. Your own language is
    // fixed in Settings and shown only as a quiet caption below.
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PartnerPicker(
        mine = vm.mine,
        partner = vm.partner,
        enabled = !vm.recording,
        onChange = vm::onPartnerChange,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        // The session now outlives navigation, so starting a fresh one is an
        // explicit act rather than a side effect of leaving the screen.
        IconButton(onClick = vm::reset, enabled = !vm.recording && vm.turns.isNotEmpty()) {
          Icon(Icons.Filled.Add, contentDescription = "Start a new session")
        }
        // Online/Offline engine toggle. Enabled only with a key + network, and
        // never mid-session (the engine is captured at Start). The speak toggle
        // lives down by the mic, next to the control it affects.
        FilledTonalIconToggleButton(
          checked = vm.online,
          enabled = canGoOnline && !vm.recording,
          onCheckedChange = vm::onOnlineChange,
        ) {
          Icon(
            painterResource(if (vm.online) R.drawable.ic_cloud else R.drawable.ic_cloud_off),
            contentDescription = if (vm.online) "Online mode" else "Offline mode",
          )
        }
      }
    }

    // Which language is "yours" — fixed; changed in Settings, not mid-conversation.
    Text(
      "You: ${vm.mine.label}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
    )

    // Why the Online toggle is unavailable (only when the user might expect it).
    if (!vm.recording && !canGoOnline) {
      val providerName = when (settings.onlineProvider) {
        OnlineProvider.OPENAI -> "OpenAI"
        OnlineProvider.GEMINI -> "Gemini"
      }
      val reason = when {
        !settings.hasApiKey(settings.onlineProvider) ->
          "Add a $providerName API key in Settings to use Online mode."
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
      if (vm.turns.isEmpty() && vm.partial.isBlank()) {
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
          items(vm.turns, key = { it.id }) { t -> TurnCard(t.transcript, t.srcLang, t.tgtLang, t.translation) }
          if (vm.partial.isNotBlank()) {
            item(key = "partial") { PartialTurn(vm.partial) }
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
      Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Mirror the speak toggle's footprint on the left so the mic stays centered.
        Spacer(Modifier.size(48.dp))
        Spacer(Modifier.weight(1f))
        Surface(
          onClick = { if (vm.recording) vm.stop() else start() },
          shape = CircleShape,
          color = if (vm.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(72.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painterResource(if (vm.recording) R.drawable.ic_stop else R.drawable.ic_mic),
              contentDescription = if (vm.recording) "Stop" else "Start recording",
              modifier = Modifier.size(32.dp),
              tint = if (vm.recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
        Spacer(Modifier.weight(1f))
        // Speak-aloud toggle, beside the mic it relates to.
        IconToggleButton(checked = vm.autoSpeak, onCheckedChange = vm::onAutoSpeakChange) {
          Icon(
            painterResource(if (vm.autoSpeak) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
            contentDescription = if (vm.autoSpeak) "Speak translations aloud" else "Translations muted",
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
