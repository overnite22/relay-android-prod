package io.github.overnite22.relay.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.core.Biometrics
import io.github.overnite22.relay.kit.Credential
import io.github.overnite22.relay.kit.SecretField
import io.github.overnite22.relay.kit.TotpCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coffre : identifiants partagés par les entrées. */
@Composable
fun VaultScreen(app: AppSession, nav: Navigator, tab: Tab) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.loadCredentials() }
    val q = query.trim().lowercase()
    val list = if (q.isEmpty()) app.credentials else app.credentials.filter { "${it.name} ${it.account} ${it.notes}".lowercase().contains(q) }
    RelayScaffold(
        "Coffre",
        actions = { IconButton({ creating = true }, Modifier.testTag("credNew")) { Icon(Icons.Filled.Add, "Nouvel identifiant") } },
    ) { padding ->
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; app.loadCredentials(); refreshing = false } }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        query, { query = it }, placeholder = { Text("Nom ou utilisateur") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Clear, "Effacer") } },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (list.isEmpty()) item {
                    EmptyState(if (q.isEmpty()) "Coffre vide" else "Aucun résultat", if (q.isEmpty()) "Ajoutez des identifiants avec le bouton +." else "", Icons.Filled.Key)
                }
                items(list, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(tab, Route.Credential(c.id)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("credential-${c.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(RelayAccent), contentAlignment = Alignment.Center) {
                            Icon(if (c.hasPrivateKey) Icons.Filled.Key else Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            Text(c.account.ifEmpty { "Sans utilisateur" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (c.hasTotp) Icon(Icons.Filled.Timer, "Code TOTP", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
    if (creating) CredentialEditorSheet(app, null) { creating = false }
}

/** Détail d'un identifiant : copie de l'utilisateur et des secrets (après déverrouillage), code TOTP. */
@Composable
fun CredentialDetailScreen(app: AppSession, id: String, back: () -> Unit) {
    val c = app.credential(id)
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (c == null) app.loadCredentials() }
    RelayScaffold(
        c?.name ?: "Identifiant", back,
        actions = { if (c != null) IconButton({ editing = true }, Modifier.testTag("credEdit")) { Icon(Icons.Filled.Edit, "Modifier") } },
    ) { padding ->
        if (c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@RelayScaffold
        }
        CredentialContent(app, c, Modifier.padding(padding))
    }
    if (editing && c != null) CredentialEditorSheet(app, c) { editing = false }
}

@Composable
private fun CredentialContent(app: AppSession, c: Credential, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf<String?>(null) }
    var totp by remember { mutableStateOf<TotpCode?>(null) }
    var fetchedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { revealed = null } }

    suspend fun secret(field: SecretField): String? {
        error = null
        if (!Biometrics.unlock(app, context)) return null
        val client = app.client ?: return null
        return try {
            client.reveal(c.id, field)
        } catch (e: Exception) {
            error = e.message
            null
        }
    }

    suspend fun loadTotp() {
        if (!Biometrics.unlock(app, context)) return
        try {
            totp = app.client?.totp(c.id)
            fetchedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Accès")
        if (c.username.isNotEmpty()) InfoRow("Utilisateur", c.account, copy = true)
        if (c.hasPassword) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mot de passe", Modifier.weight(0.4f))
                SelectionContainer(Modifier.weight(0.6f)) {
                    Text(
                        revealed ?: "••••••••", fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.testTag("credentialPassword"),
                    )
                }
                IconButton(
                    { scope.launch { revealed = if (revealed != null) null else secret(SecretField.PASSWORD) } },
                    Modifier.testTag("credentialReveal"),
                ) { Icon(if (revealed == null) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (revealed == null) "Afficher" else "Masquer") }
                CopyButton("le mot de passe", sensitive = true) { secret(SecretField.PASSWORD) }
            }
        }
        if (c.hasPrivateKey) SecretRow("Clé privée SSH") { secret(SecretField.PRIVATE_KEY) }
        if (c.hasPassphrase) SecretRow("Phrase secrète") { secret(SecretField.PASSPHRASE) }
        Footnote("Les secrets copiés sont masqués dans l'aperçu du presse-papiers et effacés après 90 secondes.")

        if (c.hasTotp) {
            SectionHeader("Code à usage unique")
            val code = totp
            if (code == null) {
                OutlinedButton({ scope.launch { loadTotp() } }, Modifier.padding(horizontal = 16.dp).testTag("credentialShowTotp")) {
                    Icon(Icons.Filled.Timer, null)
                    Text(" Afficher le code")
                }
            } else {
                LaunchedEffect(code) {
                    while (true) {
                        now = System.currentTimeMillis()
                        val remaining = code.remaining - ((now - fetchedAt) / 1000).toInt()
                        if (remaining <= 0) {
                            loadTotp()
                            break
                        }
                        delay(1000)
                    }
                }
                val remaining = maxOf(0, code.remaining - ((now - fetchedAt) / 1000).toInt())
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (code.code.length == 6) code.code.take(3) + " " + code.code.drop(3) else code.code,
                        style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        color = if (remaining <= 5) Color(0xFFE67000) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).testTag("credentialTotp"),
                    )
                    CircularProgressIndicator(
                        progress = { remaining.toFloat() / maxOf(code.period, 1) }, modifier = Modifier.size(26.dp), strokeWidth = 3.dp,
                    )
                    Text(" ${remaining}s", style = MaterialTheme.typography.bodySmall)
                    CopyButton("le code") { code.code }
                }
            }
        }
        if (c.notes.isNotEmpty()) {
            SectionHeader("Notes")
            SelectionContainer { Text(c.notes, Modifier.padding(horizontal = 16.dp)) }
        }
        error?.let { StatusBanner(it) }
        Spacer(Modifier.size(96.dp))
    }
}

@Composable
private fun SecretRow(label: String, value: suspend () -> String?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        CopyButton(label.lowercase(), sensitive = true, value = value)
    }
}
