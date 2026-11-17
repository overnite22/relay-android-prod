package io.github.overnite22.relay.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.kit.Attachment
import io.github.overnite22.relay.kit.Credential
import io.github.overnite22.relay.kit.CredentialInput
import io.github.overnite22.relay.kit.EntryInput
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.Folder
import io.github.overnite22.relay.kit.addAttachment
import io.github.overnite22.relay.kit.attachmentData
import io.github.overnite22.relay.kit.attachments
import io.github.overnite22.relay.kit.deleteAttachment
import io.github.overnite22.relay.kit.deleteCredential
import io.github.overnite22.relay.kit.deleteEntry
import io.github.overnite22.relay.kit.deleteFolder
import io.github.overnite22.relay.kit.entryInput
import io.github.overnite22.relay.kit.saveCredential
import io.github.overnite22.relay.kit.saveEntry
import io.github.overnite22.relay.kit.saveFolder
import io.github.overnite22.relay.kit.setFolderCredentials
import io.github.overnite22.relay.sessions.LocalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Identifiant hérité pour un type dans un dossier (en remontant les parents). */
fun AppSession.inheritedCredential(folder: String?, kind: EntryKind): Credential? {
    var cursor = tree.folder(folder)
    var hops = 0
    while (cursor != null && hops < 64) {
        if (cursor.credentials.containsKey(kind.raw)) return cursor.credentials[kind.raw]?.let { credential(it) }
        cursor = tree.folder(cursor.parentId)
        hops++
    }
    return null
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, tag: String = "", keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, placeholder: String = "") {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

@Composable
private fun ConfirmDelete(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } },
    )
}

/** Création ou modification d'une entrée. Les réglages avancés (RDP, SSH…) sont conservés tels quels. */
@Composable
fun EntryEditorSheet(app: AppSession, entryId: String?, initial: EntryInput, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(initial) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var loading by remember { mutableStateOf(entryId != null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Relit la fiche complète (réglages avancés) avant modification.
    LaunchedEffect(Unit) {
        if (app.credentials.isEmpty()) app.loadCredentials()
        val c = app.client ?: return@LaunchedEffect
        if (entryId != null) {
            try {
                input = c.entryInput(entryId)
                port = if (input.port > 0) input.port.toString() else ""
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    val portValue = port.toIntOrNull()
    val valid = input.name.isNotBlank() && when (input.kind) {
        EntryKind.WEB -> Uri.parse(input.url).scheme != null
        EntryKind.PVE, EntryKind.OTHER -> true
        else -> input.host.isNotBlank() && portValue != null && portValue in 1..65535
    }

    FullScreenSheet(
        if (entryId == null) "Nouvelle entrée" else "Modifier", onDismiss,
        actions = {
            SheetAction("Enregistrer", !saving && !loading && valid, "editorSave") {
                val c = app.client ?: return@SheetAction
                saving = true
                scope.launch {
                    try {
                        c.saveEntry(input.copy(name = input.name.trim(), host = input.host.trim(), port = portValue ?: 0), entryId)
                        app.refresh()
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
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@FullScreenSheet
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Field(input.name, { input = input.copy(name = it) }, "Nom", "editorName")
            if (entryId == null) {
                Segmented(EntryKind.creatable.map { it to it.title }, input.kind, tag = "editorKind") {
                    input = input.copy(kind = it, port = EntryInput.defaultPort(it))
                    port = EntryInput.defaultPort(it).takeIf { p -> p > 0 }?.toString() ?: ""
                }
            } else {
                InfoRow("Type", input.kind.title)
            }
            FolderPicker(app, "Dossier", input.folderId) { input = input.copy(folderId = it) }
            when (input.kind) {
                EntryKind.WEB -> {
                    SectionHeader("Adresse")
                    Field(input.url, { input = input.copy(url = it) }, "URL", "editorUrl", KeyboardType.Uri, placeholder = "https://…")
                }
                EntryKind.PVE -> Unit
                else -> {
                    SectionHeader("Adresse")
                    Field(input.host, { input = input.copy(host = it) }, "Hôte ou adresse IP", "editorHost", KeyboardType.Uri)
                    Field(port, { port = it.filter(Char::isDigit) }, "Port", "editorPort", KeyboardType.Number)
                }
            }
            if (input.kind != EntryKind.PVE) {
                SectionHeader("Authentification")
                Field(input.username, { input = input.copy(username = it) }, "Utilisateur (facultatif)", "editorUser")
                val inherited = app.inheritedCredential(input.folderId, input.kind)
                ChoiceField(
                    "Identifiant",
                    listOf<Pair<String?, String>>(null to (inherited?.let { "Hérité : ${it.name}" } ?: "Aucun")) + app.credentials.map { it.id to it.name },
                    input.credentialId, "editorCredential",
                ) { input = input.copy(credentialId = it) }
                Footnote("Sans identifiant choisi, l'entrée utilise celui défini sur son dossier pour ce type.")
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Notes (Markdown)")
                Spacer(Modifier.weight(1f))
                TextButton({ preview = !preview }) { Text(if (preview) "Modifier" else "Aperçu") }
            }
            if (preview) {
                Text(markdown(input.notes, MaterialTheme.colorScheme.primary), Modifier.padding(horizontal = 16.dp))
            } else {
                OutlinedTextField(
                    input.notes, { input = input.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 16.dp).testTag("editorNotes"),
                )
            }
            SwitchRow("Favori", input.favorite) { input = input.copy(favorite = it) }
            if (input.settings.isNotEmpty()) {
                Footnote("Réglages avancés conservés : ${input.settings.keys.sorted().joinToString(", ")}. Ils se modifient dans Relay web.")
            }
            error?.let { StatusBanner(it) }
            if (entryId != null) {
                TextButton({ confirmDelete = true }, Modifier.padding(horizontal = 8.dp).testTag("editorDelete")) {
                    Text("Supprimer l'entrée", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(48.dp))
        }
    }
    if (confirmDelete && entryId != null) {
        ConfirmDelete(
            "Supprimer « ${input.name} » ?",
            if (input.kind == EntryKind.PVE) "Une synchronisation Proxmox ne la recréera pas." else "Ses pièces jointes sont aussi supprimées.",
            {
                scope.launch {
                    try {
                        app.client?.deleteEntry(entryId)
                        app.refresh()
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
        ) { confirmDelete = false }
    }
}

/** Création ou modification d'un dossier, avec ses identifiants hérités par type. */
@Composable
fun FolderEditorSheet(app: AppSession, folder: Folder?, parentId: String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(folder?.name ?: "") }
    var parent by remember { mutableStateOf(folder?.parentId ?: parentId) }
    var inherited by remember { mutableStateOf(folder?.credentials ?: emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (app.credentials.isEmpty()) app.loadCredentials() }
    val inherit = "\u0000hériter"
    val none = "\u0000aucun"

    FullScreenSheet(
        if (folder == null) "Nouveau dossier" else "Modifier le dossier", onDismiss,
        actions = {
            SheetAction("Enregistrer", !saving && name.isNotBlank(), "folderSave") {
                val c = app.client ?: return@SheetAction
                saving = true
                scope.launch {
                    try {
                        val saved = c.saveFolder(name.trim(), parent, folder?.id)
                        if (folder != null) c.setFolderCredentials(saved.id, inherited)
                        app.refresh()
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
            Field(name, { name = it }, "Nom du dossier", "folderName")
            FolderPicker(app, "Dans", parent, excluding = folder?.id) { parent = it }
            if (folder != null) {
                SectionHeader("Identifiants par défaut")
                for (kind in EntryKind.creatable) {
                    val current = when {
                        !inherited.containsKey(kind.raw) -> inherit
                        inherited[kind.raw] == null -> none
                        else -> inherited[kind.raw]!!
                    }
                    ChoiceField(
                        kind.title,
                        listOf(inherit to "Hériter du parent", none to "Aucun") + app.credentials.map { it.id to it.name },
                        current, "folderCred-${kind.raw}",
                    ) { choice ->
                        inherited = when (choice) {
                            inherit -> inherited - kind.raw
                            none -> inherited + (kind.raw to null)
                            else -> inherited + (kind.raw to choice)
                        }
                    }
                }
                Footnote("Utilisés par les entrées du dossier (et des sous-dossiers) qui n'ont pas d'identifiant propre.")
            }
            error?.let { StatusBanner(it) }
            if (folder != null) {
                TextButton({ confirmDelete = true }, Modifier.padding(horizontal = 8.dp)) { Text("Supprimer le dossier", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete && folder != null) {
        ConfirmDelete("Supprimer « $name » ?", "Le dossier doit être vide, sinon Relay refuse la suppression.", {
            scope.launch {
                try {
                    app.client?.deleteFolder(folder.id)
                    app.refresh()
                    onDismiss()
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }) { confirmDelete = false }
    }
}

/** Création ou modification d'un identifiant. Secrets laissés vides = inchangés (sauf effacement demandé). */
@Composable
fun CredentialEditorSheet(app: AppSession, credential: Credential?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(credential?.name ?: "") }
    var username by remember { mutableStateOf(credential?.username ?: "") }
    var domain by remember { mutableStateOf(credential?.domain ?: "") }
    var notes by remember { mutableStateOf(credential?.notes ?: "") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null && bytes.size < 64 shl 10) privateKey = bytes.toString(Charsets.UTF_8) else error = "Ce fichier n'est pas une clé privée lisible."
    }

    /** null = inchangé ; "" = effacé ; sinon nouvelle valeur. */
    fun value(key: String, text: String): String? = if (key in clear) "" else text.ifEmpty { null }

    @Composable
    fun SecretSection(title: String, key: String, exists: Boolean, content: @Composable () -> Unit) {
        SectionHeader(title)
        if (key !in clear) content()
        if (exists) SwitchRow("Effacer la valeur enregistrée", key in clear) { clear = if (it) clear + key else clear - key }
    }

    FullScreenSheet(
        if (credential == null) "Nouvel identifiant" else "Modifier", onDismiss,
        actions = {
            SheetAction("Enregistrer", !saving && name.isNotBlank(), "credSave") {
                val c = app.client ?: return@SheetAction
                saving = true
                scope.launch {
                    try {
                        c.saveCredential(
                            CredentialInput(
                                name.trim(), username, domain, notes,
                                password = value("password", password),
                                privateKey = value("privateKey", privateKey.trim()),
                                passphrase = value("passphrase", passphrase),
                                totp = value("totp", totp.trim()),
                            ),
                            credential?.id,
                        )
                        app.loadCredentials()
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
            Field(name, { name = it }, "Nom", "credName")
            Field(username, { username = it }, "Utilisateur", "credUser")
            Field(domain, { domain = it }, "Domaine (facultatif)")
            val unchanged = { exists: Boolean? -> if (exists == true) "Inchangé" else "" }
            SecretSection("Mot de passe", "password", credential?.hasPassword == true) {
                Field(password, { password = it }, "Mot de passe", "credPassword", secret = true, placeholder = unchanged(credential?.hasPassword))
            }
            SecretSection("Clé privée SSH", "privateKey", credential?.hasPrivateKey == true) {
                OutlinedTextField(
                    privateKey, { privateKey = it }, placeholder = { Text(unchanged(credential?.hasPrivateKey).ifEmpty { "Clé privée OpenSSH ou PEM, collée ou importée" }) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).padding(horizontal = 16.dp),
                )
                TextButton({ keyPicker.launch(arrayOf("*/*")) }, Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Filled.FileOpen, null)
                    Text(" Importer depuis un fichier")
                }
            }
            SecretSection("Phrase secrète de la clé", "passphrase", credential?.hasPassphrase == true) {
                Field(passphrase, { passphrase = it }, "Phrase secrète", secret = true, placeholder = unchanged(credential?.hasPassphrase))
            }
            SecretSection("Secret TOTP", "totp", credential?.hasTotp == true) {
                Field(totp, { totp = it }, "Secret TOTP", secret = true, placeholder = if (credential?.hasTotp == true) "Inchangé" else "Base32 ou otpauth://…")
            }
            SectionHeader("Notes")
            OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 16.dp))
            error?.let { StatusBanner(it) }
            if (credential != null) {
                TextButton({ confirmDelete = true }, Modifier.padding(horizontal = 8.dp)) { Text("Supprimer l'identifiant", color = MaterialTheme.colorScheme.error) }
                Footnote("Les entrées qui l'utilisent n'auront plus d'identifiant.")
            }
            Spacer(Modifier.size(48.dp))
        }
    }
    if (confirmDelete && credential != null) {
        ConfirmDelete("Supprimer « $name » ?", "Les entrées qui l'utilisent n'auront plus d'identifiant.", {
            scope.launch {
                try {
                    app.client?.deleteCredential(credential.id)
                    app.loadCredentials()
                    app.refresh()
                    onDismiss()
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }) { confirmDelete = false }
    }
}

/** Pièces jointes d'une entrée : ajout depuis le téléphone, ouverture, partage, suppression (10 Mio par fichier). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentsSection(app: AppSession, entryId: String) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entryId) { runCatching { app.client?.attachments(entryId) }.getOrNull()?.let { items = it } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val c = app.client ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            for (uri in uris) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment ?: "fichier"
                try {
                    val data = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: continue
                    if (data.size > 10 shl 20) {
                        error = "« $name » dépasse 10 Mio."
                        continue
                    }
                    items = listOf(c.addAttachment(entryId, name, data)) + items
                    error = null
                } catch (e: Exception) {
                    error = e.message
                }
            }
            busy = false
        }
    }

    fun fetch(a: Attachment, then: (java.io.File) -> Unit) {
        val c = app.client ?: return
        busy = true
        scope.launch {
            try {
                then(LocalFiles.save(context, c.attachmentData(entryId, a.id), a.name))
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("Pièces jointes")
        if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp, top = 12.dp).size(14.dp), strokeWidth = 2.dp)
    }
    for (a in items) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { fetch(a) { LocalFiles.open(context, it) } }, onLongClick = { menuFor = a.id })
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("attachment-${a.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AttachFile, null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(a.name)
                    Text("${fileSize(context, a.size)} · ${shortDate(a.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            DropdownMenu(menuFor == a.id, { menuFor = null }) {
                DropdownMenuItem({ Text("Partager") }, { menuFor = null; fetch(a) { LocalFiles.share(context, it) } }, leadingIcon = { Icon(Icons.Filled.Share, null) })
                DropdownMenuItem({ Text("Supprimer") }, {
                    menuFor = null
                    scope.launch {
                        try {
                            app.client?.deleteAttachment(entryId, a.id)
                            items = items.filter { it.id != a.id }
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
            }
        }
    }
    TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp).testTag("attachAdd")) {
        Icon(Icons.Filled.Add, null)
        Text(" Joindre un fichier")
    }
    error?.let { StatusBanner(it) }
}
