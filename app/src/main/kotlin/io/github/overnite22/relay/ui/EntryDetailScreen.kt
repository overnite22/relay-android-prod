package io.github.overnite22.relay.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.ConnectionTest
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.EntryInput
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.sessions.SessionTarget
import kotlinx.coroutines.launch

/** Fiche d'une entrée : ouverture, disponibilité, informations, identifiant, notes et pièces jointes. */
@Composable
fun EntryDetailScreen(app: AppSession, nav: Navigator, tab: Tab, entryId: String, back: () -> Unit) {
    val entry = app.tree.entry(entryId)
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (app.credentials.isEmpty()) app.loadCredentials() }
    RelayScaffold(
        title = entry?.name ?: "Entrée",
        onBack = back,
        actions = {
            if (entry != null) {
                IconButton({ editing = true }, Modifier.testTag("entryEdit")) { Icon(Icons.Filled.Edit, "Modifier") }
                IconButton({ app.launch { app.toggleFavorite(entry) } }, Modifier.testTag("entryFavorite")) {
                    Icon(
                        if (entry.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (entry.favorite) "Retirer des favoris" else "Ajouter aux favoris", tint = RelayYellow,
                    )
                }
            }
        },
    ) { padding ->
        if (entry == null) {
            EmptyState("Entrée introuvable", "Elle a peut-être été supprimée dans Relay web.", Icons.Filled.QuestionMark)
            return@RelayScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                KindBadge(entry.kind, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${entry.kind.title} · ${pathText(app, entry)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            PrimaryActions(app, entry)
            if (entry.kind.isMonitored) Availability(app, entry)
            SectionHeader("Connexion")
            if (entry.kind == EntryKind.WEB) {
                InfoRow("URL", entry.url, copy = true)
            } else {
                InfoRow("Hôte", entry.host, copy = true)
                if (entry.port > 0) InfoRow("Port", entry.port.toString())
            }
            if (entry.username.isNotEmpty()) InfoRow("Utilisateur", entry.username, copy = true)
            val credential = app.credential(entry.credentialId)
            if (credential != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(tab, Route.Credential(credential.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Identifiant", Modifier.weight(1f))
                    Icon(Icons.Filled.Key, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(credential.name, color = MaterialTheme.colorScheme.primary)
                }
            } else if (entry.credentialId != null) {
                InfoRow("Identifiant", "Coffre")
            }
            if (entry.sourceId != null) InfoRow("Origine", "Synchronisation")
            if (entry.notes.isNotBlank()) {
                SectionHeader("Notes")
                SelectionContainer {
                    Text(
                        markdown(entry.notes, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            AttachmentsSection(app, entry.id)
            Spacer(Modifier.size(96.dp))
        }
    }
    if (editing && entry != null) {
        EntryEditorSheet(app, entry.id, EntryInput(folderId = entry.folderId, name = entry.name, kind = entry.kind)) { editing = false }
    }
}

@Composable
private fun PrimaryActions(app: AppSession, entry: Entry) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (entry.kind) {
            EntryKind.SSH, EntryKind.RDP, EntryKind.VNC, EntryKind.PVE -> {
                Button({ app.open(SessionTarget.ForEntry(entry, false)) }, Modifier.fillMaxWidth().testTag("entryOpen")) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (entry.kind == EntryKind.PVE) "Ouvrir la console" else "Ouvrir la session ${entry.kind.title}")
                }
                OutlinedButton({ app.open(SessionTarget.ForEntry(entry, true)) }, Modifier.fillMaxWidth().testTag("entryOpenRecord")) {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = RelayRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir et enregistrer")
                }
            }
            EntryKind.WEB -> {
                val uri = Uri.parse(entry.url)
                if (uri.scheme?.lowercase() in setOf("http", "https")) {
                    Button({ context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.OpenInBrowser, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ouvrir dans le navigateur")
                    }
                }
            }
            EntryKind.OTHER -> StatusBanner("Ce type d'entrée n'est pas encore pris en charge par l'app.", info = true)
        }
    }
}

@Composable
private fun Availability(app: AppSession, entry: Entry) {
    var testing by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<ConnectionTest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val test = last ?: app.status[entry.id]
    SectionHeader("Disponibilité")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(test)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(test?.statusTitle ?: "Pas encore mesurée")
            if (test != null) {
                Text(
                    "${test.address} · ${test.durationMs.toInt()} ms · ${relativeDate(test.checkedAt)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        OutlinedButton(
            onClick = {
                val c = app.client ?: return@OutlinedButton
                testing = true
                error = null
                scope.launch {
                    try {
                        last = c.testConnection(entry.id)
                        app.refreshStatus()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        testing = false
                    }
                }
            },
            enabled = !testing,
            modifier = Modifier.testTag("entryTest"),
        ) { if (testing) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Tester") }
    }
    error?.let { StatusBanner(it) }
}

@Composable
fun InfoRow(label: String, value: String, copy: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.4f))
        SelectionContainer(Modifier.weight(0.6f)) {
            Text(value, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.secondary, maxLines = 2)
        }
        if (copy) CopyButton(label) { value } else Spacer(Modifier.width(12.dp))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
