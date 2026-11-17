package io.github.overnite22.relay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.CommandRun
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.RunRequest
import io.github.overnite22.relay.kit.RunResult
import io.github.overnite22.relay.kit.RunStatus
import io.github.overnite22.relay.kit.SavedCommand
import io.github.overnite22.relay.kit.cancelRun
import io.github.overnite22.relay.kit.deleteSavedCommand
import io.github.overnite22.relay.kit.forgetHostKey
import io.github.overnite22.relay.kit.run
import io.github.overnite22.relay.kit.runHistory
import io.github.overnite22.relay.kit.saveCommand
import io.github.overnite22.relay.kit.savedCommands
import io.github.overnite22.relay.kit.startRun
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** État de la diffusion de commandes, conservé quand on navigue vers les cibles ou les sorties. */
class CommandsModel(private val app: AppSession) {
    private val store = app.context.getSharedPreferences("prefs", 0)
    var command by mutableStateOf("")
    var sudo by mutableStateOf(false)
    var timeout by mutableIntStateOf(store.getInt("commandTimeout", 30))
    var concurrency by mutableIntStateOf(store.getInt("commandConcurrency", 10))
    var run by mutableStateOf<CommandRun?>(null)
    var starting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf<List<SavedCommand>>(emptyList())
    private var poll: Job? = null

    val targets: List<Entry> get() = app.tree.entries.filter { it.id in app.commandTargets && it.kind == EntryKind.SSH }

    fun persistLimits() {
        store.edit().putInt("commandTimeout", timeout).putInt("commandConcurrency", concurrency).apply()
    }

    fun start(ids: List<String>) {
        val c = app.client ?: return
        starting = true
        error = null
        persistLimits()
        app.scope.launch {
            try {
                val started = c.startRun(RunRequest(ids, command.trim(), sudo, timeout, concurrency))
                run = started
                follow(started.id)
            } catch (e: Exception) {
                error = e.message
            } finally {
                starting = false
            }
        }
    }

    /** Suit l'exécution toutes les secondes jusqu'à la fin. */
    private fun follow(id: String) {
        poll?.cancel()
        poll = app.scope.launch {
            while (true) {
                delay(1000)
                val current = runCatching { app.client?.run(id) }.getOrNull() ?: continue
                run = current
                if (current.finished) return@launch
            }
        }
    }

    fun cancel() {
        val id = run?.id ?: return
        app.launch { app.client?.cancelRun(id) }
    }

    suspend fun loadSaved() {
        runCatching { app.client?.savedCommands() }.getOrNull()?.let { saved = it }
    }

    fun takeDraft() {
        app.commandDraft?.let { (c, s) ->
            command = c
            sudo = s
            app.commandDraft = null
        }
    }
}

/** Diffusion d'une commande SSH sur plusieurs serveurs, exécutée par le serveur Relay. */
@Composable
fun CommandsScreen(app: AppSession, nav: Navigator, tab: Tab) {
    val m = app.commands
    val focus = LocalFocusManager.current
    var library by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { m.loadSaved() }
    LaunchedEffect(app.commandDraft) { m.takeDraft() }
    val targets = m.targets
    val run = m.run
    RelayScaffold(
        "Commande SSH",
        actions = { IconButton({ nav.push(tab, Route.History) }, Modifier.testTag("commandHistory")) { Icon(Icons.Filled.History, "Historique") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(tab, Route.CommandTargets) }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("commandTargets"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Serveurs SSH", Modifier.weight(1f))
                    Text(if (targets.isEmpty()) "Aucun" else "${targets.size} sélectionné(s)", color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary)
                }
                if (targets.isNotEmpty()) {
                    Text(
                        targets.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("COMMANDE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    Box {
                        TextButton({ library = true }, Modifier.testTag("commandLibrary")) {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(18.dp))
                            Text(" Bibliothèque")
                        }
                        DropdownMenu(library, { library = false }) {
                            if (m.saved.isEmpty()) DropdownMenuItem({ Text("Aucune commande enregistrée") }, {}, enabled = false)
                            for (s in m.saved) DropdownMenuItem({ Text(s.name) }, { library = false; m.command = s.command; m.sudo = s.sudo })
                            HorizontalDivider()
                            DropdownMenuItem(
                                { Text("Enregistrer la commande…") }, { library = false; newName = ""; naming = true },
                                enabled = m.command.isNotBlank(),
                            )
                            DropdownMenuItem({ Text("Gérer la bibliothèque") }, { library = false; nav.push(tab, Route.SavedCommands) })
                        }
                    }
                }
                OutlinedTextField(
                    m.command, { m.command = it }, placeholder = { Text("uptime") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(horizontal = 16.dp).testTag("commandText"),
                )
                SwitchRow("Exécuter avec sudo", m.sudo) { m.sudo = it }
                Stepper("Délai par serveur : ${m.timeout} s", { m.timeout = (m.timeout - 5).coerceAtLeast(5) }, { m.timeout = (m.timeout + 5).coerceAtMost(600) })
                Stepper("Connexions en parallèle : ${m.concurrency}", { m.concurrency = (m.concurrency - 1).coerceAtLeast(1) }, { m.concurrency = (m.concurrency + 1).coerceAtMost(50) })
                Footnote("Relay se connecte à chaque serveur avec les identifiants du coffre. Une clé de serveur qui change est refusée.")
                Button(
                    { focus.clearFocus(); m.start(targets.map { it.id }) },
                    enabled = !m.starting && targets.isNotEmpty() && m.command.isNotBlank() && (run?.finished ?: true),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("commandRun"),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(if (m.starting) " Démarrage…" else " Exécuter")
                }
                m.error?.let { StatusBanner(it) }
            }
            if (run != null) results(app, nav, tab, run, m)
            item { Spacer(Modifier.size(88.dp)) }
        }
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Enregistrer la commande") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Nom") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    naming = false
                    val name = newName.trim()
                    if (name.isNotEmpty()) app.launch { app.client?.saveCommand(SavedCommand(name = name, command = m.command, sudo = m.sudo))?.let { m.saved = m.saved + it } }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton({ naming = false }) { Text("Annuler") } },
        )
    }
}

private fun LazyListScope.results(app: AppSession, nav: Navigator, tab: Tab, run: CommandRun, m: CommandsModel) {
    item {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (run.finished) "Résultats : ${run.ok} réussie(s), ${run.failed} échec(s)" else "En cours…",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).testTag("commandSummary"),
            )
            if (run.finished) {
                val failed = run.results.orEmpty().filter { it.status != RunStatus.OK }.map { it.entryId }
                if (failed.isNotEmpty()) TextButton({ m.start(failed) }) { Text("Relancer les échecs") }
            } else {
                TextButton({ m.cancel() }, Modifier.testTag("commandCancel")) { Text("Annuler", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    items(run.results.orEmpty(), key = { "r-" + it.entryId }) { r ->
        Box(Modifier.clickable { nav.push(tab, Route.RunOutput(r, run.command)) }.testTag("result-${r.name}")) { RunResultRow(r) }
    }
}

@Composable
private fun Stepper(label: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(minus) { Icon(Icons.Filled.Remove, "Moins") }
        IconButton(plus) { Icon(Icons.Filled.Add, "Plus") }
    }
}

@Composable
fun RunResultRow(r: RunResult) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (r.status) {
            RunStatus.PENDING -> Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.secondary)
            RunStatus.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            RunStatus.OK -> Icon(Icons.Filled.CheckCircle, "Réussie", tint = RelayGreen)
            RunStatus.FAILED -> Icon(Icons.Filled.Cancel, "Échec", tint = Color(0xFFE67000))
            RunStatus.ERROR, RunStatus.UNKNOWN -> Icon(Icons.Filled.Error, "Erreur", tint = RelayRed)
            RunStatus.CANCELED -> Icon(Icons.Filled.StopCircle, "Annulée", tint = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name)
            Text(
                listOfNotNull(r.address, r.exitCode?.let { "code $it" }, if (r.status.isFinal) "${r.durationMs.toInt()} ms" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
            if (r.error.isNotEmpty()) {
                Text(r.error, style = MaterialTheme.typography.bodySmall, color = RelayRed, maxLines = 2)
            } else {
                r.output.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(r.status.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/** Sortie complète d'un serveur. */
@Composable
fun RunOutputScreen(app: AppSession, r: RunResult, back: () -> Unit) {
    var forgotten by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    RelayScaffold(r.name, back) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                RunResultRow(r)
                if (r.fingerprint.isNotEmpty()) InfoRow("Empreinte", r.fingerprint, copy = true)
                if (r.newHostKey) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, Modifier.size(16.dp))
                    Text("  Clé du serveur mémorisée à cette exécution.", style = MaterialTheme.typography.bodySmall)
                }
                if ((r.status == RunStatus.ERROR && r.fingerprint.isNotEmpty()) || r.hostKeyChanged) {
                    OutlinedButton(
                        {
                            scope.launch {
                                try {
                                    app.client?.forgetHostKey(r.address)
                                    forgotten = true
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }
                        },
                        enabled = !forgotten, modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Icon(Icons.Filled.KeyOff, null)
                        Text(if (forgotten) " Clé oubliée : relancez la commande" else " Oublier la clé mémorisée")
                    }
                    Footnote("À faire seulement si le changement de clé est attendu (réinstallation du serveur).")
                }
                error?.let { StatusBanner(it) }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SORTIE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    CopyButton("la sortie") { r.output }
                }
                SelectionContainer {
                    Text(
                        r.output.ifEmpty { "(aucune sortie)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("runOutput"),
                    )
                }
                if (r.truncated) Footnote("Sortie tronquée par le serveur.")
            }
        }
    }
}

/** Choix des serveurs SSH : par dossier ou individuellement. */
@Composable
fun CommandTargetsScreen(app: AppSession, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    fun entriesUnder(folder: String?): List<Entry> {
        val out = app.tree.entries(folder).filter { it.kind == EntryKind.SSH }.toMutableList()
        for (child in app.tree.folders(folder)) out += entriesUnder(child.id)
        return out
    }
    fun toggle(e: Entry) {
        app.commandTargets = if (e.id in app.commandTargets) app.commandTargets - e.id else app.commandTargets + e.id
    }
    RelayScaffold("Serveurs", back) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Filtrer") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton({ app.commandTargets = emptySet() }, enabled = app.commandTargets.isNotEmpty(), modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Tout désélectionner")
                }
            }
            if (query.isNotEmpty()) {
                val q = query.lowercase()
                items(app.tree.entries.filter { it.kind == EntryKind.SSH && it.searchText.contains(q) }, key = { it.id }) { e ->
                    TargetRow(e.name, pathText(app, e), e.id in app.commandTargets, 0, "target-${e.name}") { toggle(e) }
                }
            } else {
                fun walk(folder: String?, depth: Int, scope: LazyListScope) {
                    for (child in app.tree.folders(folder)) {
                        val inside = entriesUnder(child.id)
                        if (inside.isEmpty()) continue
                        scope.item(key = "f-" + child.id) {
                            val all = inside.all { it.id in app.commandTargets }
                            TargetRow(child.name, "${inside.size} serveur(s)", all, depth, "targetFolder-${child.name}", folder = true) {
                                val ids = inside.map { it.id }.toSet()
                                app.commandTargets = if (all) app.commandTargets - ids else app.commandTargets + ids
                            }
                        }
                        walk(child.id, depth + 1, scope)
                    }
                    for (e in app.tree.entries(folder).filter { it.kind == EntryKind.SSH }) {
                        scope.item(key = "e-" + e.id) { TargetRow(e.name, e.target, e.id in app.commandTargets, depth, "target-${e.name}") { toggle(e) } }
                    }
                }
                walk(null, 0, this)
            }
        }
    }
}

@Composable
private fun TargetRow(title: String, subtitle: String, on: Boolean, depth: Int, tag: String, folder: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp + (depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folder) Icon(Icons.Filled.Folder, null, tint = RelayYellow) else KindBadge(EntryKind.SSH, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (folder) FontWeight.SemiBold else FontWeight.Normal)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (on) RelayAccent else MaterialTheme.colorScheme.secondary)
    }
}

/** Bibliothèque de commandes enregistrées (partagée avec Relay web). */
@Composable
fun SavedCommandsScreen(app: AppSession, back: () -> Unit) {
    val m = app.commands
    var editing by remember { mutableStateOf<SavedCommand?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { m.loadSaved() }
    RelayScaffold(
        "Bibliothèque", back,
        actions = { IconButton({ editing = SavedCommand(name = "", command = "") }) { Icon(Icons.Filled.Add, "Ajouter") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            error?.let { item { StatusBanner(it) } }
            items(m.saved, key = { it.id }) { s ->
                Box {
                    Column(
                        Modifier.fillMaxWidth().clickable { menuFor = s.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row {
                            Text(s.name)
                            if (s.sudo) Text("  sudo", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE67000), fontWeight = FontWeight.Bold)
                        }
                        Text(s.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                    }
                    DropdownMenu(menuFor == s.id, { menuFor = null }) {
                        DropdownMenuItem({ Text("Utiliser") }, { menuFor = null; m.command = s.command; m.sudo = s.sudo; back() })
                        DropdownMenuItem({ Text("Modifier") }, { menuFor = null; editing = s })
                        DropdownMenuItem({ Text("Supprimer") }, {
                            menuFor = null
                            scope.launch {
                                try {
                                    app.client?.deleteSavedCommand(s.id)
                                    m.saved = m.saved.filter { it.id != s.id }
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }
                        }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                    }
                }
            }
        }
    }
    editing?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial.name) }
        var command by remember(initial) { mutableStateOf(initial.command) }
        var sudo by remember(initial) { mutableStateOf(initial.sudo) }
        FullScreenSheet(
            if (initial.id.isEmpty()) "Nouvelle commande" else "Modifier", { editing = null },
            actions = {
                SheetAction("Enregistrer", name.isNotBlank() && command.isNotBlank(), "savedSave") {
                    scope.launch {
                        try {
                            val updated = app.client?.saveCommand(initial.copy(name = name.trim(), command = command, sudo = sudo)) ?: return@launch
                            m.saved = if (m.saved.any { it.id == updated.id }) m.saved.map { if (it.id == updated.id) updated else it } else m.saved + updated
                            editing = null
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                OutlinedTextField(
                    command, { command = it }, label = { Text("Commande") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SwitchRow("sudo", sudo) { sudo = it }
            }
        }
    }
}

/** Historique des exécutions (200 dernières côté serveur). */
@Composable
fun CommandHistoryScreen(app: AppSession, nav: Navigator, tab: Tab, back: () -> Unit) {
    var runs by remember { mutableStateOf<List<CommandRun>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        loading = true
        try {
            runs = app.client?.runHistory() ?: emptyList()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    RelayScaffold("Historique", back) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { StatusBanner(it) } }
                items(runs, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().clickable { nav.push(tab, Route.RunDetail(r.id)) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("run-${r.id}")) {
                        Text(r.command, fontFamily = FontFamily.Monospace, maxLines = 2)
                        Text(
                            "${shortDate(r.at)} · ${r.targets} serveur(s) · ${r.ok} OK · ${r.failed} échec(s)${if (r.sudo) " · sudo" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RunDetailScreen(app: AppSession, nav: Navigator, tab: Tab, id: String, back: () -> Unit) {
    var run by remember { mutableStateOf<CommandRun?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try {
            run = app.client?.run(id)
        } catch (e: Exception) {
            error = e.message
        }
    }
    RelayScaffold(run?.let { shortDate(it.at) } ?: "Exécution", back) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            error?.let { item { StatusBanner(it) } }
            run?.let { r ->
                item {
                    SectionHeader("Commande")
                    SelectionContainer { Text(r.command, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp)) }
                    TextButton({
                        app.commandTargets = r.results.orEmpty().map { it.entryId }.toSet()
                        app.commandDraft = r.command to r.sudo
                        nav.stacks.getValue(tab).clear()
                    }, Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                        Text(" Reprendre dans l'éditeur")
                    }
                    SectionHeader("Résultats")
                }
                items(r.results.orEmpty(), key = { it.entryId }) { res ->
                    Box(Modifier.clickable { nav.push(tab, Route.RunOutput(res, r.command)) }) { RunResultRow(res) }
                }
            }
        }
    }
}
