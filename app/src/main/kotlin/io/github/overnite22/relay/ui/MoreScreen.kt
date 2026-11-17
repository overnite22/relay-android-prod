package io.github.overnite22.relay.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.BuildConfigInfo
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.AuditEvent
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.QuickConnectRequest
import io.github.overnite22.relay.kit.SessionOptions
import io.github.overnite22.relay.sessions.SessionTarget
import kotlinx.coroutines.launch

/** Onglet « Plus » : outils, administration, réglages et appairage. */
@Composable
fun MoreScreen(app: AppSession, nav: Navigator, tab: Tab) {
    val context = LocalContext.current
    var sshText by remember { mutableFloatStateOf(app.prefs.sshText) }
    var rdpResolution by remember { mutableFloatStateOf(app.prefs.rdpResolution) }
    var biometrics by remember { mutableStateOf(app.prefs.biometrics) }
    var keepAlive by remember { mutableStateOf(app.prefs.keepAlive) }
    var confirmUnpair by remember { mutableStateOf(false) }
    RelayScaffold("Plus") { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Outils")
            NavRow(Icons.Filled.Bolt, "Connexion rapide", "moreQuick") { nav.push(tab, Route.QuickConnect) }
            NavRow(Icons.Filled.History, "Journal d'activité", "moreAudit") { nav.push(tab, Route.Audit) }
            NavRow(Icons.Filled.Movie, "Enregistrements", "moreRecordings") { nav.push(tab, Route.Recordings) }
            NavRow(Icons.Filled.Layers, "Espaces de travail", "moreWorkspaces") { nav.push(tab, Route.Workspaces) }
            SectionHeader("Administration")
            NavRow(Icons.Filled.Sync, "Sources de synchro", "moreSources") { nav.push(tab, Route.Sources) }
            NavRow(Icons.Filled.ContentCopy, "Doublons", "moreDuplicates") { nav.push(tab, Route.Duplicates) }
            NavRow(Icons.Filled.Download, "Importer depuis RDM", "moreImport") { nav.push(tab, Route.Import) }
            NavRow(Icons.Filled.Backup, "Sauvegarde", "moreBackup") { nav.push(tab, Route.Backup) }
            SectionHeader("Sessions")
            ChoiceField("Texte SSH", listOf(0.7f to "Petit", 0.85f to "Normal", 1.0f to "Grand"), sshText) { sshText = it; app.prefs.sshText = it }
            ChoiceField("Résolution RDP", listOf(1.0f to "Écran ×1", 1.5f to "Écran ×1,5", 2.0f to "Écran ×2"), rdpResolution) {
                rdpResolution = it
                app.prefs.rdpResolution = it
            }
            SwitchRow("Garder les sessions en arrière-plan", keepAlive, "Notification « sessions ouvertes » tant qu'une session est active.") {
                keepAlive = it
                app.prefs.keepAlive = it
                app.updateKeepAlive()
            }
            Footnote("S'applique aux prochaines connexions. Une résolution plus élevée affiche plus de choses, en plus petit : pincez pour zoomer.")
            SectionHeader("Sécurité")
            SwitchRow("Déverrouillage pour les secrets", biometrics, tag = "moreBiometrics") { biometrics = it; app.prefs.biometrics = it }
            Footnote("Empreinte, visage ou code de l'appareil, demandé avant d'afficher ou de copier un mot de passe, une clé ou un code TOTP, puis valable une minute.")
            SectionHeader("Serveur")
            InfoRow("Adresse", app.account?.serverDisplay ?: "—")
            InfoRow("Compte", app.info?.user ?: "—")
            InfoRow("Version de Relay", app.info?.version ?: "—")
            InfoRow("Version de l'app", BuildConfigInfo.version(context))
            TextButton({ confirmUnpair = true }, Modifier.padding(horizontal = 8.dp).testTag("moreUnpair")) {
                Text("Désappairer ce téléphone", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(96.dp))
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Désappairer ce téléphone ?") },
            text = { Text("Le jeton est effacé du téléphone. Pensez aussi à le révoquer dans Relay web → Administration → Appareils.") },
            confirmButton = { TextButton({ confirmUnpair = false; app.unpair() }) { Text("Désappairer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text("Annuler") } },
        )
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary)
    }
}

/** Connexion rapide : SSH ou RDP vers une adresse, sans créer de fiche. */
@Composable
fun QuickConnectScreen(app: AppSession, back: () -> Unit) {
    val store = remember { app.context.getSharedPreferences("prefs", 0) }
    var kind by remember { mutableStateOf(if (store.getString("quickKind", "ssh") == "rdp") EntryKind.RDP else EntryKind.SSH) }
    var host by remember { mutableStateOf(store.getString("quickHost", "") ?: "") }
    var port by remember { mutableStateOf("") }
    var credentialId by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var record by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (app.credentials.isEmpty()) app.loadCredentials() }
    val defaultPort = if (kind == EntryKind.RDP) 3389 else 22
    val portValue = port.trim().let { p -> if (p.isEmpty()) defaultPort else p.toIntOrNull()?.takeIf { it in 1..65535 } }

    RelayScaffold("Connexion rapide", back) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Segmented(listOf(EntryKind.SSH to "SSH", EntryKind.RDP to "RDP"), kind, tag = "quickKind") { kind = it }
            OutlinedTextField(
                host, { host = it }, label = { Text("Hôte ou adresse IP") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("quickHost"),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, label = { Text("Port ($defaultPort)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("quickPort"),
            )
            SectionHeader("Authentification")
            ChoiceField(
                "Identifiant du coffre", listOf<Pair<String?, String>>(null to "Saisie ponctuelle") + app.credentials.map { it.id to it.name },
                credentialId, "quickCredential",
            ) { credentialId = it }
            if (credentialId == null) {
                OutlinedTextField(username, { username = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                if (kind == EntryKind.RDP) {
                    OutlinedTextField(domain, { domain = it }, label = { Text("Domaine (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                OutlinedTextField(
                    password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SwitchRow("Enregistrer la session", record) { record = it }
            Footnote("Le mot de passe saisi ici sert uniquement à cette connexion : il n'est ni mémorisé ni ajouté au coffre.")
            Button(
                onClick = {
                    val p = portValue ?: return@Button
                    val h = host.trim()
                    store.edit().putString("quickKind", kind.raw).putString("quickHost", h).apply()
                    val request = QuickConnectRequest.of(
                        kind, h, p,
                        username = if (credentialId == null) username else "",
                        domain = if (credentialId == null) domain else "",
                        password = if (credentialId == null) password else "",
                        credentialId = credentialId,
                        options = SessionOptions(1280, 720, record = record),
                    )
                    password = ""
                    app.open(SessionTarget.Quick(request, "${kind.title} $h"))
                },
                enabled = host.isNotBlank() && portValue != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("quickConnect"),
            ) {
                Icon(Icons.Filled.Bolt, null)
                Text(" Connecter")
            }
        }
    }
}

val auditLabels = mapOf(
    "session.open" to "Ouverture de session", "session.close" to "Fin de session",
    "entry.create" to "Entrée créée", "entry.update" to "Entrée modifiée", "entry.delete" to "Entrée supprimée",
    "folder.create" to "Dossier créé", "folder.update" to "Dossier modifié", "folder.delete" to "Dossier supprimé",
    "credential.create" to "Identifiant créé", "credential.update" to "Identifiant modifié", "credential.delete" to "Identifiant supprimé",
    "credential.reveal" to "Secret affiché / copié", "credential.totp" to "Code TOTP affiché",
    "source.create" to "Source ajoutée", "source.update" to "Source modifiée", "source.delete" to "Source supprimée",
    "source.sync" to "Synchronisation", "import.rdm" to "Import RDM",
    "device.create" to "Appareil appairé", "device.delete" to "Appareil révoqué", "recording.delete" to "Enregistrement supprimé",
    "command.run" to "Commande SSH", "workspace.save" to "Espace de travail enregistré",
    "folder.credentials" to "Identifiants du dossier", "backup.export" to "Sauvegarde exportée", "backup.restore" to "Sauvegarde restaurée",
)

/** Journal d'activité du serveur Relay. */
@Composable
fun AuditScreen(app: AppSession, back: () -> Unit) {
    var events by remember { mutableStateOf<List<AuditEvent>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val c = app.client ?: return
        loading = true
        try {
            events = c.audit(300)
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val q = query.lowercase()
    val shown = if (q.isEmpty()) events else events.filter { e ->
        listOf(e.user, e.action, auditLabels[e.action] ?: "", e.target, e.detail).any { it.lowercase().contains(q) }
    }
    RelayScaffold("Journal d'activité", back) { padding ->
        PullToRefreshBox(loading, { scope.launch { load() } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        query, { query = it }, placeholder = { Text("Filtrer") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                error?.let { item { StatusBanner(it) } }
                items(shown, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row {
                            Text(auditLabels[e.action] ?: e.action, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(shortDate(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (e.target.isNotEmpty()) Text(e.target, maxLines = 2)
                        Text(listOf(e.user, e.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}
