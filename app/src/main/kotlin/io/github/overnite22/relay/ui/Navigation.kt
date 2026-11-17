package io.github.overnite22.relay.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Destinations empilables dans un onglet. */
sealed class Route {
    data class Folder(val id: String) : Route()
    data class Entry(val id: String) : Route()
    data class Credential(val id: String) : Route()
    data object QuickConnect : Route()
    data object Recordings : Route()
    data class Player(val recording: io.github.overnite22.relay.kit.Recording) : Route()
    data object Workspaces : Route()
    data object Audit : Route()
    data object Sources : Route()
    data object Duplicates : Route()
    data object Import : Route()
    data object Backup : Route()
    data object History : Route()
    data object CommandTargets : Route()
    data object SavedCommands : Route()
    data class RunOutput(val result: io.github.overnite22.relay.kit.RunResult, val command: String) : Route()
    data class RunDetail(val id: String) : Route()
    data class SourceDetail(val id: String) : Route()
}

enum class Tab(val title: String) { CONNECTIONS("Connexions"), AVAILABILITY("Disponibilité"), COMMANDS("Commandes"), VAULT("Coffre"), MORE("Plus") }

/** Piles de navigation, une par onglet (le retour d'Android dépile l'onglet courant). */
class Navigator {
    val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf() }

    fun push(tab: Tab, route: Route) {
        stacks.getValue(tab).add(route)
    }

    fun pop(tab: Tab): Boolean {
        val s = stacks.getValue(tab)
        if (s.isEmpty()) return false
        s.removeAt(s.lastIndex)
        return true
    }

    fun top(tab: Tab): Route? = stacks.getValue(tab).lastOrNull()
}
