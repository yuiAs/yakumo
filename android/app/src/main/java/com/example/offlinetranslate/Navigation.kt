package com.example.offlinetranslate

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.offlinetranslate.ui.history.HistoryDetailScreen
import com.example.offlinetranslate.ui.history.SessionHistoryScreen
import com.example.offlinetranslate.ui.session.NewSessionScreen
import com.example.offlinetranslate.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(NewSession)
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  // Top-level destinations are roots: replace the stack so Back exits cleanly.
  fun navigateTop(key: NavKey) {
    backStack.clear()
    backStack.add(key)
    scope.launch { drawerState.close() }
  }

  val current = backStack.lastOrNull()

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet {
        Text(
          "やくも",
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.titleLarge,
        )
        HorizontalDivider()
        NavigationDrawerItem(
          label = { Text("New session") },
          selected = current == NewSession,
          onClick = { navigateTop(NewSession) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
          label = { Text("Session history") },
          selected = current == SessionHistory || current is HistoryDetail,
          onClick = { navigateTop(SessionHistory) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
          label = { Text("Settings") },
          selected = current == SettingsKey,
          onClick = { navigateTop(SettingsKey) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
      }
    },
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<NewSession> {
            AppBarScaffold("New session", onMenu = { scope.launch { drawerState.open() } }) {
              NewSessionScreen(it)
            }
          }
          entry<SessionHistory> {
            AppBarScaffold("Session history", onMenu = { scope.launch { drawerState.open() } }) {
              SessionHistoryScreen(onOpen = { id -> backStack.add(HistoryDetail(id)) }, modifier = it)
            }
          }
          entry<SettingsKey> {
            AppBarScaffold("Settings", onMenu = { scope.launch { drawerState.open() } }) {
              SettingsScreen(it)
            }
          }
          entry<HistoryDetail> { key ->
            AppBarScaffold("Session", onBack = { backStack.removeLastOrNull() }) {
              HistoryDetailScreen(key.id, it)
            }
          }
        },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBarScaffold(
  title: String,
  onMenu: (() -> Unit)? = null,
  onBack: (() -> Unit)? = null,
  content: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          when {
            onBack != null ->
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
              }
            onMenu != null ->
              IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
              }
          }
        },
      )
    },
  ) { padding -> content(Modifier.padding(padding)) }
}
