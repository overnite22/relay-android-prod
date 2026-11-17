package io.github.overnite22.relay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Sorting
import io.github.overnite22.relay.kit.Workspace
import io.github.overnite22.relay.sessions.SessionTarget
import kotlinx.coroutines.launch

/** Espaces de travail : groupes d'entrées ouverts d'un geste (partagés avec Relay web). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspacesScreen(app: AppSession, back: () -> Unit) {
    var items by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Workspace?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val c = app.client ?: return
        loading = true
        try {
            items = c.workspaces()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    fun delete(w: Workspace) = scope.launch {
        try {
            app.client?.deleteWorkspace(w.id)
            items = items.filter { it.id != w.id }
        } catch (e: Exception) {
            error = e.message
        }
    }
    LaunchedEffect(Unit) { load() }
    RelayScaffold(
        "Espaces de travail", back,
        actions = { IconButton({ editing = Workspace(name = "") }, Modifier.testTag("workspaceNew")) { Icon(Icons.Filled.Add, "Nouvel espace") } },
    ) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { StatusBanner(it) } }
                if (app.sessions.isNotEmpty()) item {
                    OutlinedButton(
                        {
                            val ids = app.sessions.mapNotNull { (it.target as? SessionTarget.ForEntry)?.entry?.id }.distinct()
                            editing = Workspace(name = "", entryIds = ids)
                        },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("workspaceFromSessions"),
                    ) {
                        Icon(Icons.Filled.Save, null)
                        Text(" Enregistrer les sessions ouvertes")
                    }
                }
                if (items.isEmpty() && !loading && error == null) item {
                    EmptyState("Aucun espace de travail", "Regroupez des entrées pour les ouvrir ensemble.", Icons.Filled.Layers)
                }
                items(items, key = { it.id }) { w ->
                    val entries = w.entryIds.mapNotNull { app.tree.entry(it) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { app.openAll(entries) }, onLongClick = { menuFor = w.id })
                                .padding(horizontal = 16.dp, vertical = 12.dp).testTag("workspace-${w.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Layers, null, tint = RelayAccent)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.name)
                                Text(entries.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${entries.size} ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Icon(Icons.Filled.PlayCircle, "Ouvrir les sessions", tint = RelayAccent, modifier = Modifier.size(30.dp))
                        }
                        DropdownMenu(menuFor == w.id, { menuFor = null }) {
                            DropdownMenuItem({ Text("Ouvrir les sessions") }, { menuFor = null; app.openAll(entries) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
                            DropdownMenuItem({ Text("Modifier") }, { menuFor = null; editing = w }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            DropdownMenuItem({ Text("Supprimer") }, { menuFor = null; delete(w) }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                }
                item { Footnote("Appui long sur un espace pour le modifier ou le supprimer.") }
            }
        }
    }
    editing?.let { w ->
        WorkspaceEditor(app, w, { editing = null }) { saved ->
            items = listOf(saved) + items.filter { it.id != saved.id }
            editing = null
        }
    }
}

@Composable
private fun WorkspaceEditor(app: AppSession, initial: Workspace, onDismiss: () -> Unit, onSaved: (Workspace) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var ids by remember { mutableStateOf(initial.entryIds) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val q = query.lowercase()
    val candidates = app.tree.entries.filter { it.kind.opensNatively && (q.isEmpty() || it.searchText.contains(q)) }.sortedWith(Sorting.byName { it.name })
    FullScreenSheet(
        if (initial.id.isEmpty()) "Nouvel espace" else "Modifier l'espace", onDismiss,
        actions = {
            SheetAction("Enregistrer", !saving && name.isNotBlank() && ids.isNotEmpty(), "workspaceSave") {
                val c = app.client ?: return@SheetAction
                saving = true
                scope.launch {
                    try {
                        onSaved(c.saveWorkspace(initial.copy(name = name.trim(), entryIds = ids)))
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        saving = false
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Nom de l'espace") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("workspaceName"))
                Footnote("${ids.size} entrée(s) sélectionnée(s), 50 au plus.")
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Filtrer les entrées") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                error?.let { StatusBanner(it) }
            }
            items(candidates, key = { it.id }) { e ->
                val on = e.id in ids
                Row(
                    Modifier.fillMaxWidth().clickable {
                        ids = if (on) ids - e.id else if (ids.size < 50) ids + e.id else ids
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KindBadge(e.kind)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        Text(pathText(app, e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Icon(
                        if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (on) RelayAccent else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
