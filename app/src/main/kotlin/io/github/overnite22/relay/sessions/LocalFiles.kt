package io.github.overnite22.relay.sessions

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Fichiers reçus (sessions, pièces jointes, enregistrements) : copie locale puis partage Android. */
object LocalFiles {
    fun save(context: Context, data: ByteArray, name: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "fichier" }.take(120)
        val file = File(dir, safe)
        file.writeBytes(data)
        return file
    }

    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Feuille de partage (enregistrer dans Fichiers, envoyer par messagerie…). */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ouvre le fichier dans l'app adaptée (PDF, image…). */
    fun open(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(view) }.onFailure { share(context, file) }
    }
}
