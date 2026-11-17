package io.github.overnite22.relay.kit.guac

import io.github.overnite22.relay.kit.RelayJson
import io.github.overnite22.relay.kit.Sorting
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Élément d'un dossier distant (index JSON de guacd : chemin → type MIME). */
data class RemoteItem(val path: String, val isDirectory: Boolean) {
    val id: String get() = path
    val name: String get() = RemotePath.basename(path)

    companion object {
        /** Décode un index de dossier ; dossiers d'abord, puis tri naturel par nom. */
        fun parseIndex(data: ByteArray): List<RemoteItem> {
            val index = RelayJson.default.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()), data.toString(Charsets.UTF_8),
            )
            val natural = NaturalOrder()
            return index.map { RemoteItem(it.key, it.value == GuacClientCore.DIRECTORY_MIMETYPE) }
                .sortedWith { a, b ->
                    if (a.isDirectory != b.isDirectory) (if (a.isDirectory) -1 else 1) else natural.compare(a.name, b.name)
                }
        }
    }
}

/** Tri « naturel » insensible à la casse : a2 avant A10 (comme le Finder ou l'explorateur Windows). */
class NaturalOrder : Comparator<String> {
    private val collator = Sorting.collator()

    override fun compare(a: String, b: String): Int {
        val ca = chunks(a)
        val cb = chunks(b)
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val tx = x.trimStart('0')
                val ty = y.trimStart('0')
                if (tx.length != ty.length) tx.length - ty.length else tx.compareTo(ty)
            } else {
                collator.compare(x, y)
            }
            if (r != 0) return r
        }
        return ca.size - cb.size
    }

    private fun chunks(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val digit = s[i].isDigit()
            var j = i
            while (j < s.length && s[j].isDigit() == digit) j++
            out.add(s.substring(i, j))
            i = j
        }
        return out
    }
}

object RemotePath {
    fun basename(path: String): String {
        val trimmed = if (path.endsWith("/") && path.length > 1) path.dropLast(1) else path
        return trimmed.split("/").lastOrNull { it.isNotEmpty() } ?: "/"
    }

    fun parent(path: String): String {
        var trimmed = path
        while (trimmed.length > 1 && trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
        val slash = trimmed.lastIndexOf('/')
        return if (slash <= 0) "/" else trimmed.substring(0, slash)
    }

    fun join(dir: String, name: String): String = (if (dir.endsWith("/")) dir else "$dir/") + name
}
