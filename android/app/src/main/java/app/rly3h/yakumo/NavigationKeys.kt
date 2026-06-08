package app.rly3h.yakumo

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object NewSession : NavKey

@Serializable data object SessionHistory : NavKey

@Serializable data object SettingsKey : NavKey

/** Detail view for one saved session, identified by its stored id. */
@Serializable data class HistoryDetail(val id: String) : NavKey
