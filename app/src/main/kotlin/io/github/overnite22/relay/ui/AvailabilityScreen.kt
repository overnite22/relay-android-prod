package io.github.overnite22.relay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.Sorting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Disponibilité : entrées dont le port ne répond pas, puis celles qui répondent. */
@Composable
fun AvailabilityScreen(app: AppSession, nav: Navigator, tab: Tab) {
    var filter by rememberSaveable { mutableStateOf(0) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var retest by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            app.refreshStatus()
            delay(30_000)
        }
    }

    fun inFolder(e: Entry) = folder == null || app.tree.path(e.folderId).any { it.id == folder }
    val monitored = app.tree.entries.filter { it.kind.isMonitored && inFolder(it) }
    val down = monitored.filter { app.status[it.id]?.reachable == false }
    val up = monitored.filter { app.status[it.id]?.reachable == true }
    val unknown = monitored.filter { app.status[it.id] == null }
    val shown = listOf(down, up, unknown)[filter].sortedWith(Sorting.byName { it.name })

    RelayScaffold("Disponibilité") { padding ->
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; app.refreshStatus(); refreshing = false } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Counter(up.size, "Accessibles", RelayGreen, Modifier.weight(1f))
                        Counter(down.size, "Injoignables", RelayRed, Modifier.weight(1f))
                        Counter(unknown.size, "Sans mesure", Color.Gray, Modifier.weight(1f))
                    }
                    Segmented(listOf(0 to "Injoignables", 1 to "Accessibles", 2 to "Sans mesure"), filter, tag = "availabilityFilter") { filter = it }
                    FolderPicker(app, "Dossier", folder, rootLabel = "Tous") { folder = it }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    app.retest(monitored) { done, total -> retest = done to total }
                                    retest = null
                                }
                            },
                            enabled = retest == null && monitored.isNotEmpty(),
                            modifier = Modifier.testTag("availabilityRetest"),
                        ) {
                            Icon(Icons.Filled.Refresh, null)
                            Text(if (folder == null) " Retester tout" else " Retester ce dossier")
                        }
                        Spacer(Modifier.weight(1f))
                        retest?.let { (d, t) -> Text("$d/$t", style = MaterialTheme.typography.bodySmall) }
                    }
                    Footnote("Relay teste le port de chaque entrée SSH, RDP et VNC toutes les minutes, depuis le serveur.")
                }
                if (shown.isEmpty()) item {
                    Text(if (filter == 0) "Tout répond." else "Aucune entrée.", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(16.dp))
                }
                items(shown, key = { it.id }) { entry ->
                    Column {
                        EntryRow(app, nav, tab, entry, showPath = true)
                        val test = app.status[entry.id]
                        if (test != null && !test.reachable) {
                            Text(
                                "${test.statusTitle} · ${relativeDate(test.checkedAt)}",
                                style = MaterialTheme.typography.labelSmall, color = RelayRed,
                                modifier = Modifier.padding(start = 60.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
}

@Composable
private fun Counter(n: Int, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
