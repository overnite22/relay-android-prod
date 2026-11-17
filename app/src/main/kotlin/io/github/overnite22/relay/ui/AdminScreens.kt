package io.github.overnite22.relay.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.DuplicateGroup
import io.github.overnite22.relay.kit.ImportPlan
import io.github.overnite22.relay.kit.ImportResult
import io.github.overnite22.relay.kit.SourceResult
import io.github.overnite22.relay.kit.SyncSource
import io.github.overnite22.relay.kit.SyncSourceInput
import io.github.overnite22.relay.kit.applyImport
import io.github.overnite22.relay.kit.clearExclusions
import io.github.overnite22.relay.kit.deleteSource
import io.github.overnite22.relay.kit.duplicates
import io.github.overnite22.relay.kit.exportBackup
import io.github.overnite22.relay.kit.merge
import io.github.overnite22.relay.kit.previewImport
import io.github.overnite22.relay.kit.restoreBackup
import io.github.overnite22.relay.kit.saveSource
import io.github.overnite22.relay.kit.sources
import io.github.overnite22.relay.kit.syncSource
import io.github.overnite22.relay.kit.testSource
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, tag: String = "", secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, caps: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false,
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

/** Résultat d'une action (vert si réussie). */
@Composable
private fun ResultBanner(ok: Boolean, text: String) = StatusBanner(text, info = ok)

private fun interval(min: Int) = when {
    min == 0 -> "manuelle"
    min < 60 -> "toutes les $min min"
    else -> "toutes les ${min / 60} h"
}

/** Lit un document choisi par l'utilisateur : nom et contenu. */
private suspend fun readDocument(context: android.content.Context, uri: Uri): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "fichier"
    name to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
}

// region Sources

/** Sources de synchronisation Proxmox et NetBox. */
@Composable
fun SourcesScreen(app: AppSession, nav: Navigator, tab: Tab, back: () -> Unit) {
    var sources by remember { mutableStateOf<List<SyncSource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        loading = true
        try {
            sources = app.client?.sources() ?: emptyList()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    RelayScaffold(
        "Sources de synchro", back,
        actions = {
            Box {
                IconButton({ addMenu = true }, Modifier.testTag("sourceAdd")) { Icon(Icons.Filled.Add, "Ajouter") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem({ Text("Proxmox VE") }, { addMenu = false; creating = "proxmox" })
                    DropdownMenuItem({ Text("NetBox") }, { addMenu = false; creating = "netbox" })
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { StatusBanner(it) } }
                if (sources.isEmpty() && !loading && error == null) item {
                    EmptyState("Aucune source", "Ajoutez Proxmox VE ou NetBox pour créer les entrées automatiquement.", Icons.Filled.Sync)
                }
                items(sources, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(tab, Route.SourceDetail(s.id)) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("source-${s.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            Text("${s.kindTitle} · ${interval(s.intervalMin)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            if (s.lastStatus.isNotEmpty()) Text(s.lastStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
    creating?.let { kind -> SourceEditorSheet(app, null, kind, { creating = null }) { scope.launch { load() } } }
}

@Composable
fun SourceDetailScreen(app: AppSession, id: String, back: () -> Unit) {
    var source by remember { mutableStateOf<SyncSource?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        try {
            source = app.client?.sources()?.firstOrNull { it.id == id }
        } catch (e: Exception) {
            message = false to (e.message ?: "Erreur")
        }
    }
    LaunchedEffect(id) { reload() }
    fun action(key: String, run: suspend () -> SourceResult?) {
        busy = key
        scope.launch {
            try {
                run()?.let { message = it.ok to it.message }
                reload()
            } catch (e: Exception) {
                message = false to (e.message ?: "Erreur")
            } finally {
                busy = null
            }
        }
    }
    val s = source
    RelayScaffold(
        s?.name ?: "Source", back,
        actions = { if (s != null) IconButton({ editing = true }, Modifier.testTag("sourceEdit")) { Icon(Icons.Filled.Edit, "Modifier") } },
    ) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@RelayScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            InfoRow("Type", s.kindTitle)
            InfoRow("Adresse", s.url)
            if (s.tokenId.isNotEmpty()) InfoRow("Jeton", s.tokenId)
            InfoRow("Vérification TLS", if (s.verifyTls) "Oui" else "Non")
            InfoRow("Synchronisation", interval(s.intervalMin).replaceFirstChar { it.uppercase() })
            s.lastSyncAt?.let { InfoRow("Dernière", shortDate(it)) }
            if (s.lastStatus.isNotEmpty()) Footnote(s.lastStatus)
            SectionHeader("Actions")
            ActionRow(Icons.Filled.VerifiedUser, "Tester la connexion", busy == "test", busy == null, "sourceTest") { action("test") { app.client?.testSource(s.id) } }
            ActionRow(Icons.Filled.Sync, "Synchroniser maintenant", busy == "sync", busy == null, "sourceSync") {
                action("sync") { app.client?.syncSource(s.id).also { app.refresh() } }
            }
            ActionRow(Icons.AutoMirrored.Filled.Undo, "Réautoriser les entrées supprimées", busy == "clear", busy == null, "sourceClear") {
                action("clear") { SourceResult(true, app.client?.clearExclusions(s.id) ?: "") }
            }
            Footnote("Les entrées supprimées ou fusionnées ne sont pas recréées par la synchronisation, sauf réautorisation.")
            message?.let { (ok, text) -> ResultBanner(ok, text) }
            TextButton({ confirmDelete = true }, Modifier.padding(horizontal = 8.dp)) { Text("Supprimer la source", color = MaterialTheme.colorScheme.error) }
            Footnote("Les entrées déjà créées restent dans l'arborescence.")
        }
    }
    if (editing && s != null) SourceEditorSheet(app, s, s.kind, { editing = false }) { scope.launch { reload() } }
    if (confirmDelete && s != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer « ${s.name} » ?") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        runCatching { app.client?.deleteSource(s.id) }
                        back()
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, running: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f))
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SourceEditorSheet(app: AppSession, source: SyncSource?, kind: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var input by remember { mutableStateOf(source?.let { SyncSourceInput.of(it) } ?: SyncSourceInput(kind)) }
    var secret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val proxmox = kind == "proxmox"
    LaunchedEffect(Unit) { if (app.credentials.isEmpty()) app.loadCredentials() }
    fun option(key: String, default: Boolean) = (input.options[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun credentialOption(key: String) = (input.options[key] as? JsonPrimitive)?.contentOrNull
    FullScreenSheet(
        if (source != null) "Modifier la source" else if (proxmox) "Nouvelle source Proxmox" else "Nouvelle source NetBox", onDismiss,
        actions = {
            SheetAction("Enregistrer", !saving && input.name.isNotBlank() && input.url.isNotBlank() && (source != null || secret.isNotEmpty()), "sourceSave") {
                val c = app.client ?: return@SheetAction
                saving = true
                scope.launch {
                    try {
                        c.saveSource(input.copy(kind = kind, secret = secret.ifEmpty { null }), source?.id)
                        onSaved()
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        saving = false
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Field(input.name, { input = input.copy(name = it) }, "Nom", "sourceName")
            Field(input.url, { input = input.copy(url = it) }, if (proxmox) "https://pve.example.com:8006" else "https://netbox.example.com", keyboard = KeyboardType.Uri)
            SwitchRow("Vérifier le certificat TLS", input.verifyTls) { input = input.copy(verifyTls = it) }
            SectionHeader("Accès")
            if (proxmox) Field(input.tokenId, { input = input.copy(tokenId = it) }, "utilisateur@pve!jeton")
            Field(
                secret, { secret = it },
                if (source?.hasSecret == true) "Secret inchangé" else if (proxmox) "Secret du jeton" else "Jeton d'API", secret = true,
            )
            Footnote(if (proxmox) "Jeton en lecture seule (Sys.Audit, VM.Audit, VM.Console)." else "Jeton NetBox en lecture seule.")
            SectionHeader("Contenu")
            val toggles = if (proxmox) listOf("nodeEntries" to "Entrées SSH et web des nœuds", "guestEntries" to "Entrées SSH/RDP des invités")
            else listOf("devices" to "Équipements", "vms" to "Machines virtuelles")
            for ((key, title) in toggles) SwitchRow(title, option(key, true)) { input = input.copy(options = input.options + (key to JsonPrimitive(it))) }
            for ((key, title) in listOf("sshCredentialId" to "Identifiant SSH par défaut", "rdpCredentialId" to "Identifiant RDP par défaut")) {
                ChoiceField(title, listOf<Pair<String?, String>>(null to "Aucun") + app.credentials.map { it.id to it.name }, credentialOption(key)) { v ->
                    input = input.copy(options = if (v == null) input.options - key else input.options + (key to JsonPrimitive(v)))
                }
            }
            ChoiceField("Synchronisation", listOf(0, 15, 30, 60, 360, 1440).map { it to interval(it).replaceFirstChar { c -> c.uppercase() } }, input.intervalMin) {
                input = input.copy(intervalMin = it)
            }
            error?.let { StatusBanner(it) }
        }
    }
}

// endregion

// region Doublons

/** Doublons : entrées visant la même cible (DNS résolu côté serveur), fusion. */
@Composable
fun DuplicatesScreen(app: AppSession, back: () -> Unit) {
    val store = remember { app.context.getSharedPreferences("prefs", 0) }
    var ignored by remember { mutableStateOf(store.getStringSet("ignoredDuplicates", emptySet())!!.toSet()) }
    var groups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun setIgnored(v: Set<String>) {
        ignored = v
        store.edit().putStringSet("ignoredDuplicates", v).apply()
    }
    suspend fun load() {
        loading = true
        try {
            groups = app.client?.duplicates() ?: emptyList()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val visible = groups.filter { it.key !in ignored }
    RelayScaffold("Doublons", back) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { StatusBanner(it) } }
                if (visible.isEmpty() && !loading) item { EmptyState("Aucun doublon", "Chaque cible n'apparaît qu'une fois.", Icons.Filled.Verified) }
                for (g in visible) {
                    item(key = "g-" + g.key) {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(g.label)
                            Spacer(Modifier.weight(1f))
                            TextButton({ setIgnored(ignored + g.key) }) { Text("Ignorer") }
                        }
                    }
                    items(g.entries, key = { g.key + it.id }) { e ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            KindBadge(e.kind)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name)
                                Text("${pathText(app, e)} · ${e.target}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton({
                                scope.launch {
                                    try {
                                        app.client?.merge(e.id, g.entries.map { it.id }.filter { it != e.id })
                                        app.refresh()
                                        load()
                                    } catch (ex: Exception) {
                                        error = ex.message
                                    }
                                }
                            }, Modifier.testTag("merge-${e.name}")) { Icon(Icons.AutoMirrored.Filled.CallMerge, "Garder ${e.name} et fusionner") }
                        }
                    }
                    item(key = "f-" + g.key) { Footnote("Le bouton fusion garde cette entrée : elle reprend l'identifiant, l'utilisateur, les notes, le favori et les pièces jointes des autres.") }
                }
                if (ignored.isNotEmpty()) item {
                    TextButton({ setIgnored(emptySet()) }, Modifier.padding(horizontal = 8.dp)) { Text("Réafficher les groupes ignorés (${ignored.size})") }
                }
            }
        }
    }
}

// endregion

// region Import RDM

/** Import d'un export Remote Desktop Manager (.rdm) avec aperçu. */
@Composable
fun ImportScreen(app: AppSession, back: () -> Unit) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var folder by remember { mutableStateOf("Import RDM") }
    var sshDefault by remember { mutableStateOf<String?>(null) }
    var rdpDefault by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (app.credentials.isEmpty()) app.loadCredentials() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        result = null
        scope.launch {
            try {
                val (name, data) = readDocument(context, uri)
                file = data
                fileName = name
                plan = app.client?.previewImport(data)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    RelayScaffold("Importer depuis RDM", back) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("importPick")) {
                Icon(Icons.Filled.FileOpen, null)
                Text(if (file == null) " Choisir un fichier .rdm" else " Changer de fichier ($fileName)")
            }
            Footnote("Dans RDM : Fichier → Exporter → Exporter toutes les entrées, format .rdm, sans mot de passe. Rien n'est créé avant « Importer ».")
            plan?.let { p ->
                SectionHeader("Aperçu")
                InfoRow("Dossiers", "${p.folders.size}")
                InfoRow("Entrées", "${p.entries.size}")
                InfoRow("Identifiants", "${p.credentials.size}")
                InfoRow("Ignorées", "${p.skipped.size}")
                for (w in p.warnings) StatusBanner(w)
                if (p.skipped.isNotEmpty()) {
                    SectionHeader("Non importées")
                    for (s in p.skipped) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(s.name)
                            Text("${s.type} : ${s.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                SectionHeader("Destination")
                Field(folder, { folder = it }, "Dossier de destination", "importFolder")
                val creds = listOf<Pair<String?, String>>(null to "Aucun") + app.credentials.map { it.id to it.name }
                ChoiceField("Identifiant SSH par défaut", creds, sshDefault) { sshDefault = it }
                ChoiceField("Identifiant RDP par défaut", creds, rdpDefault) { rdpDefault = it }
                Footnote("Les mots de passe chiffrés par RDM ne peuvent pas être relus : ressaisissez-les ensuite dans le coffre.")
                Button(
                    {
                        val data = file ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                result = app.client?.applyImport(data, folder, sshDefault ?: "", rdpDefault ?: "")
                                plan = null
                                file = null
                                app.refresh()
                                app.loadCredentials()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && folder.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("importApply"),
                ) {
                    Icon(Icons.Filled.Download, null)
                    Text(" Importer")
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
            result?.let { ResultBanner(true, "Import terminé : ${it.entries} entrée(s), ${it.credentials} identifiant(s), ${it.folders} dossier(s).") }
            error?.let { StatusBanner(it) }
        }
    }
}

// endregion

// region Sauvegarde

/** Sauvegarde chiffrée : export vers le téléphone, restauration (remplace toutes les données). */
@Composable
fun BackupScreen(app: AppSession, back: () -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var restoreFile by remember { mutableStateOf<ByteArray?>(null) }
    var restoreName by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var restoreTyped by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pending
        pending = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(data) } }
                done = "Sauvegarde enregistrée."
                password = ""
                confirm = ""
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, data) = readDocument(context, uri)
                restoreFile = data
                restoreName = name
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    RelayScaffold("Sauvegarde", back) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Exporter")
            Field(password, { password = it }, "Mot de passe de la sauvegarde", "backupPassword", secret = true)
            Field(confirm, { confirm = it }, "Confirmer le mot de passe", "backupConfirm", secret = true)
            Button(
                {
                    val c = app.client ?: return@Button
                    busy = true
                    error = null
                    done = null
                    scope.launch {
                        try {
                            pending = c.exportBackup(password)
                            saver.launch("relay-${LocalDate.now()}.relay")
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && password.length >= 8 && password == confirm,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("backupExport"),
            ) {
                Icon(Icons.Filled.Upload, null)
                Text(" Exporter")
            }
            Footnote("Archive chiffrée de toutes les données (entrées, coffre, sources, historique). 8 caractères au moins ; sans ce mot de passe, elle est illisible.")
            SectionHeader("Restaurer")
            OutlinedButton({ opener.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("backupPick")) {
                Icon(Icons.Filled.FileOpen, null)
                Text(if (restoreFile == null) " Choisir une archive" else " Archive : $restoreName")
            }
            if (restoreFile != null) {
                Field(restorePassword, { restorePassword = it }, "Mot de passe de l'archive", secret = true)
                Field(restoreTyped, { restoreTyped = it }, "Tapez RESTAURER pour confirmer", "backupTyped", caps = true)
                Button(
                    {
                        val c = app.client ?: return@Button
                        val data = restoreFile ?: return@Button
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                c.restoreBackup(data, restorePassword)
                                done = "Sauvegarde restaurée."
                                restoreFile = null
                                restorePassword = ""
                                restoreTyped = ""
                                app.refresh()
                                app.loadCredentials()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && restorePassword.isNotEmpty() && restoreTyped == "RESTAURER",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("backupRestore"),
                ) { Text("Restaurer et remplacer toutes les données") }
            }
            Footnote("La restauration remplace tout le contenu du serveur Relay, pour tous les appareils.")
            if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
            done?.let { ResultBanner(true, it) }
            error?.let { StatusBanner(it) }
        }
    }
}

// endregion
