package io.github.overnite22.relay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.EntryInput
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.deleteEntry
import io.github.overnite22.relay.sessions.SessionTarget
import kotlinx.coroutines.launch

/** Dossier de l'arborescence ; à la racine : recherche globale, favoris et récents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(app: AppSession, nav: Navigator, tab: Tab, folderId: String?, back: (() -> Unit)?) {
    var query by remember { mutableStateOf("") }
    var newEntry by remember { mutableStateOf<EntryInput?>(null) }
    var folderEditor by remember { mutableStateOf<Pair<Boolean, io.github.overnite22.relay.kit.Folder?>?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tree = app.tree

    RelayScaffold(
        title = tree.folder(folderId)?.name ?: "Connexions",
        onBack = back,
        actions = {
            Box {
                IconButton(onClick = { addMenu = true }, modifier = Modifier.testTag("addMenu")) { Icon(Icons.Filled.Add, "Ajouter") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(
                        { Text("Nouvelle entrée") }, { addMenu = false; newEntry = EntryInput(folderId = folderId) },
                        leadingIcon = { Icon(Icons.Filled.NoteAdd, null) }, modifier = Modifier.testTag("addEntry"),
                    )
                    DropdownMenuItem(
                        { Text("Nouveau dossier") }, { addMenu = false; folderEditor = true to null },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) }, modifier = Modifier.testTag("addFolder"),
                    )
                }
            }
            if (folderId != null) {
                Box {
                    IconButton(onClick = { actionsMenu = true }, modifier = Modifier.testTag("folderActions")) { Icon(Icons.Filled.MoreVert, "Actions") }
                    DropdownMenu(actionsMenu, { actionsMenu = false }) {
                        DropdownMenuItem(
                            { Text("Modifier le dossier") }, { actionsMenu = false; folderEditor = false to tree.folder(folderId) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) }, modifier = Modifier.testTag("editFolder"),
                        )
                        val ssh = sshEntries(app, folderId)
                        DropdownMenuItem(
                            { Text("Commande SSH sur ce dossier") },
                            {
                                actionsMenu = false
                                app.commandTargets = ssh.map { it.id }.toSet()
                                app.requestedTab = "commands"
                            },
                            enabled = ssh.isNotEmpty(), leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                        )
                        DropdownMenuItem(
                            { Text("Ouvrir toutes les sessions") }, { actionsMenu = false; app.openAll(tree.entries(folderId)) },
                            leadingIcon = { Icon(Icons.Filled.ViewAgenda, null) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = app.loading,
            onRefresh = { scope.launch { app.refresh() } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("folderList")) {
                if (folderId == null) {
                    item {
                        OutlinedTextField(
                            query, { query = it },
                            placeholder = { Text("Nom, hôte, utilisateur, notes") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Clear, "Effacer") } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("search"),
                        )
                    }
                    app.errorMessage?.let { item { StatusBanner(it) } }
                }
                if (query.isNotEmpty()) {
                    val results = tree.search(query)
                    if (results.isEmpty()) {
                        item { EmptyState("Aucun résultat", "Aucune entrée ne correspond à « $query ».", Icons.Filled.Search) }
                    } else {
                        item { SectionHeader("${results.size} résultat${if (results.size > 1) "s" else ""}") }
                        items(results, key = { "s-" + it.id }) { EntryRow(app, nav, tab, it, showPath = true) }
                    }
                } else {
                    if (folderId == null) {
                        val favorites = app.favoriteEntries
                        if (favorites.isNotEmpty()) {
                            item { SectionHeader("Favoris") }
                            items(favorites, key = { "f-" + it.id }) { EntryRow(app, nav, tab, it) }
                        }
                        val recents = app.recentEntries.take(5)
                        if (recents.isNotEmpty()) {
                            item { SectionHeader("Récents") }
                            items(recents, key = { "r-" + it.id }) { EntryRow(app, nav, tab, it, showPath = true) }
                        }
                    }
                    val folders = tree.folders(folderId)
                    val entries = tree.entries(folderId)
                    if (folders.isNotEmpty()) {
                        if (folderId == null) item { SectionHeader("Dossiers") }
                        items(folders, key = { "d-" + it.id }) { folder ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { nav.push(tab, Route.Folder(folder.id)) })
                                    .padding(horizontal = 16.dp, vertical = 14.dp).testTag("folder-${folder.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = RelayYellow)
                                Spacer(Modifier.width(14.dp))
                                Text(folder.name, Modifier.weight(1f))
                                Text("${count(app, folder.id)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    if (entries.isNotEmpty()) {
                        if (folderId == null) item { SectionHeader("Entrées à la racine") }
                        items(entries, key = { "e-" + it.id }) { EntryRow(app, nav, tab, it) }
                    }
                    if (folders.isEmpty() && entries.isEmpty() && !app.loading) {
                        item { EmptyState("Dossier vide", "Aucune entrée ici.", Icons.Filled.Folder) }
                    }
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
    newEntry?.let { input -> EntryEditorSheet(app, null, input) { newEntry = null } }
    folderEditor?.let { (_, folder) -> FolderEditorSheet(app, folder, folderId) { folderEditor = null } }
}

private fun sshEntries(app: AppSession, folder: String?): List<Entry> {
    val out = app.tree.entries(folder).filter { it.kind == EntryKind.SSH }.toMutableList()
    for (child in app.tree.folders(folder)) out += sshEntries(app, child.id)
    return out
}

private fun count(app: AppSession, folder: String): Int {
    var total = app.tree.entries(folder).size
    for (child in app.tree.folders(folder)) total += count(app, child.id)
    return total
}

fun pathText(app: AppSession, entry: Entry): String {
    val names = app.tree.path(entry.folderId).map { it.name }
    return if (names.isEmpty()) "Racine" else names.joinToString(" › ")
}

/** Ligne d'une entrée : toucher = fiche, appui long = actions (ouvrir, enregistrer, favori, modifier…). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(app: AppSession, nav: Navigator, tab: Tab, entry: Entry, showPath: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val status = app.status[entry.id]
    val subtitle = if (showPath) {
        val base = pathText(app, entry)
        if (entry.target.isEmpty()) base else "$base · ${entry.target}"
    } else if (entry.target.isEmpty()) entry.kind.title else "${entry.kind.title} · ${entry.target}"
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { nav.push(tab, Route.Entry(entry.id)) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("entry-${entry.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(entry.kind)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Star, "Favori", Modifier.size(14.dp), tint = RelayYellow)
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.kind.isMonitored) StatusDot(status)
        }
        DropdownMenu(menu, { menu = false }) {
            if (entry.kind.opensNatively) {
                DropdownMenuItem({ Text("Ouvrir") }, { menu = false; app.open(SessionTarget.ForEntry(entry, false)) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
                DropdownMenuItem({ Text("Ouvrir et enregistrer") }, { menu = false; app.open(SessionTarget.ForEntry(entry, true)) }, leadingIcon = { Icon(Icons.Filled.FiberManualRecord, null) })
            }
            if (entry.host.isNotEmpty()) {
                DropdownMenuItem({ Text("Copier l'hôte") }, { menu = false; copyToClipboard(context, "Hôte", entry.host) }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
            }
            DropdownMenuItem(
                { Text(if (entry.favorite) "Retirer des favoris" else "Ajouter aux favoris") },
                { menu = false; app.launch { app.toggleFavorite(entry) } },
                leadingIcon = { Icon(if (entry.favorite) Icons.Filled.StarBorder else Icons.Filled.Star, null) },
            )
            HorizontalDivider()
            DropdownMenuItem({ Text("Modifier") }, { menu = false; editing = true }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
            DropdownMenuItem({ Text("Dupliquer") }, { menu = false; app.launch { app.duplicate(entry) } }, leadingIcon = { Icon(Icons.Filled.LibraryAdd, null) })
            DropdownMenuItem({ Text("Supprimer") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
        }
    }
    if (editing) EntryEditorSheet(app, entry.id, EntryInput(folderId = entry.folderId, name = entry.name, kind = entry.kind)) { editing = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer « ${entry.name} » ?") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    app.launch {
                        app.client?.deleteEntry(entry.id)
                        app.refresh()
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
