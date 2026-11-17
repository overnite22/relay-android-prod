package io.github.overnite22.relay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.sessions.SessionScreen

/** Interface principale : onglets Connexions, Disponibilité, Commandes, Coffre et Plus. */
@Composable
fun HomeScreen(app: AppSession) {
    var tab by rememberSaveable { mutableStateOf(Tab.CONNECTIONS) }
    val nav = remember { Navigator() }

    LaunchedEffect(Unit) { app.refresh() }
    LaunchedEffect(app.requestedTab) {
        if (app.requestedTab == "commands") tab = Tab.COMMANDS
        app.requestedTab = null
    }

    val presented = app.presentedSession
    if (presented != null) {
        SessionScreen(presented, app)
        return
    }

    BackHandler(enabled = nav.top(tab) != null) { nav.pop(tab) }

    Scaffold(
        bottomBar = {
            if (nav.top(tab) !is Route.Player) NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { if (tab == t) nav.stacks.getValue(t).clear() else tab = t },
                        icon = {
                            val icon = when (t) {
                                Tab.CONNECTIONS -> Icons.Filled.Lan
                                Tab.AVAILABILITY -> Icons.Filled.MonitorHeart
                                Tab.COMMANDS -> Icons.Filled.Terminal
                                Tab.VAULT -> Icons.Filled.Key
                                Tab.MORE -> Icons.Filled.MoreHoriz
                            }
                            val down = if (t == Tab.AVAILABILITY) downCount(app) else 0
                            if (down > 0) BadgedBox(badge = { Badge { Text("$down") } }) { Icon(icon, null) } else Icon(icon, null)
                        },
                        label = { Text(t.title, maxLines = 1) },
                        modifier = Modifier.testTag("tab-${t.name}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val route = nav.top(tab)
            val back = { nav.pop(tab); Unit }
            if (route == null) {
                when (tab) {
                    Tab.CONNECTIONS -> FolderScreen(app, nav, tab, null, null)
                    Tab.AVAILABILITY -> AvailabilityScreen(app, nav, tab)
                    Tab.COMMANDS -> CommandsScreen(app, nav, tab)
                    Tab.VAULT -> VaultScreen(app, nav, tab)
                    Tab.MORE -> MoreScreen(app, nav, tab)
                }
            } else {
                RouteContent(route, app, nav, tab, back)
            }
            if (app.sessions.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { app.presentedSessionId = app.sessions.last().uid },
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).testTag("sessionsPill"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ViewAgenda, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (app.sessions.size == 1) app.sessions.first().title else "${app.sessions.size} sessions ouvertes",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.KeyboardArrowUp, null)
                    }
                }
            }
        }
    }
}

/** Écran d'une destination empilée. */
@Composable
fun RouteContent(route: Route, app: AppSession, nav: Navigator, tab: Tab, back: () -> Unit) {
    when (route) {
        is Route.Folder -> FolderScreen(app, nav, tab, route.id, back)
        is Route.Entry -> EntryDetailScreen(app, nav, tab, route.id, back)
        is Route.Credential -> CredentialDetailScreen(app, route.id, back)
        Route.QuickConnect -> QuickConnectScreen(app, back)
        Route.Recordings -> RecordingsScreen(app, nav, tab, back)
        is Route.Player -> RecordingPlayerScreen(app, route.recording, back)
        Route.Workspaces -> WorkspacesScreen(app, back)
        Route.Audit -> AuditScreen(app, back)
        Route.Sources -> SourcesScreen(app, nav, tab, back)
        is Route.SourceDetail -> SourceDetailScreen(app, route.id, back)
        Route.Duplicates -> DuplicatesScreen(app, back)
        Route.Import -> ImportScreen(app, back)
        Route.Backup -> BackupScreen(app, back)
        Route.History -> CommandHistoryScreen(app, nav, tab, back)
        Route.CommandTargets -> CommandTargetsScreen(app, back)
        Route.SavedCommands -> SavedCommandsScreen(app, back)
        is Route.RunOutput -> RunOutputScreen(app, route.result, back)
        is Route.RunDetail -> RunDetailScreen(app, nav, tab, route.id, back)
    }
}

private fun downCount(app: AppSession): Int =
    app.tree.entries.count { it.kind.isMonitored && app.status[it.id]?.let { s -> !s.reachable } == true }
