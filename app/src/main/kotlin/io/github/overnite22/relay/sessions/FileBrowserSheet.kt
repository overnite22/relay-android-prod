package io.github.overnite22.relay.sessions

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.guac.RemoteItem
import io.github.overnite22.relay.kit.guac.RemotePath
import io.github.overnite22.relay.ui.Footnote
import io.github.overnite22.relay.ui.FullScreenSheet
import io.github.overnite22.relay.ui.RelayGreen
import io.github.overnite22.relay.ui.RelayRed
import io.github.overnite22.relay.ui.RelayYellow
import io.github.overnite22.relay.ui.SectionHeader
import io.github.overnite22.relay.ui.StatusBanner
import io.github.overnite22.relay.ui.fileSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Transfer(val id: Int, val name: String, val upload: Boolean, val total: Long) {
    var done by mutableStateOf(0L)
    /** null = en cours, "" = terminé, sinon message d'erreur. */
    var result by mutableStateOf<String?>(null)
    var file by mutableStateOf<File?>(null)
}

/** Navigateur de fichiers d'une session : SFTP (SSH) ou lecteur « Relay » (RDP). */
@Composable
fun FileBrowserSheet(controller: SessionController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var items by remember { mutableStateOf<List<RemoteItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val transfers = remember { mutableStateListOf<Transfer>() }
    val isRdp = controller.target.kind == EntryKind.RDP
    val fsName = controller.filesystem?.second ?: "Relay"

    fun load(dir: String) {
        error = null
        items = null
        scope.launch {
            try {
                items = controller.list(dir)
                path = dir
            } catch (e: Exception) {
                error = e.message
                items = emptyList()
            }
        }
    }

    fun download(item: RemoteItem) {
        val t = Transfer(transfers.size, item.name, false, 0)
        transfers.add(0, t)
        scope.launch {
            try {
                val f = controller.download(item.path) { t.done = it.toLong() }
                t.file = f
                t.result = ""
                LocalFiles.share(context, f)
            } catch (e: Exception) {
                t.result = e.message ?: "Erreur"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val dir = path
        scope.launch {
            for (uri in uris) {
                val (name, size) = describe(context, uri)
                val t = Transfer(transfers.size, name, true, size)
                transfers.add(0, t)
                try {
                    if (size > 512L shl 20) throw FileError("Fichier trop volumineux (512 Mo au plus).")
                    val data = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileError("Fichier illisible.")
                    }
                    controller.upload(data, RemotePath.join(dir, name), context.contentResolver.getType(uri) ?: LocalFiles.mimeType(name)) { t.done = it.toLong() }
                    t.done = data.size.toLong()
                    t.result = ""
                } catch (e: Exception) {
                    t.result = e.message ?: "Erreur"
                }
            }
            if (dir == path) load(dir)
        }
    }

    LaunchedEffect(Unit) { load("/") }

    FullScreenSheet(
        if (isRdp) "Lecteur $fsName" else "Fichiers (SFTP)", onDismiss, dismissLabel = "OK",
        actions = {
            IconButton({ load(path) }) { Icon(Icons.Filled.Refresh, "Actualiser") }
            IconButton({ picker.launch(arrayOf("*/*")) }, Modifier.testTag("filesUpload")) { Icon(Icons.Filled.Upload, "Envoyer") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (path != "/") item {
                FileRow(Icons.Filled.SubdirectoryArrowLeft, "Dossier parent", MaterialTheme.colorScheme.primary, "filesParent") { load(RemotePath.parent(path)) }
            }
            val list = items
            if (list == null && error == null) item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("  Lecture du dossier…")
                }
            }
            if (list != null && list.isEmpty() && error == null) item { Text("Dossier vide", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(16.dp)) }
            items(list.orEmpty(), key = { it.path }) { item ->
                FileRow(
                    if (item.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, item.name,
                    if (item.isDirectory) RelayYellow else MaterialTheme.colorScheme.secondary, "file-${item.name}",
                ) { if (item.isDirectory) load(item.path) else download(item) }
            }
            if (isRdp) item { Footnote("Côté Windows, le lecteur « $fsName » est dans Ce PC. Un fichier copié dans son dossier Download arrive sur le téléphone.") }
            error?.let { item { StatusBanner(it) } }
            if (transfers.isNotEmpty()) item { SectionHeader("Transferts") }
            items(transfers, key = { "t" + it.id + it.name }) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("transfer-${t.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.upload) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1)
                        when (val r = t.result) {
                            null -> if (t.total > 0) LinearProgressIndicator({ (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                            else Text(fileSize(context, t.done), style = MaterialTheme.typography.bodySmall)
                            "" -> Text("Terminé", style = MaterialTheme.typography.bodySmall, color = RelayGreen)
                            else -> Text(r, style = MaterialTheme.typography.bodySmall, color = RelayRed)
                        }
                    }
                    t.file?.let { f -> IconButton({ LocalFiles.share(context, f) }) { Icon(Icons.Filled.Share, "Partager") } }
                }
            }
        }
    }
}

@Composable
private fun FileRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, tint: androidx.compose.ui.graphics.Color, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(name)
    }
}

private fun describe(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
    var size = 0L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return name to size
}
