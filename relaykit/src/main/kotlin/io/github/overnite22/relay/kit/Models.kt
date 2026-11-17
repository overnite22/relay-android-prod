package io.github.overnite22.relay.kit

import java.text.Collator
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Type d'entrée Relay. Les types inconnus (versions futures du serveur) deviennent [OTHER]. */
@Serializable(with = EntryKindSerializer::class)
enum class EntryKind(override val raw: String, val title: String) : RawValued {
    RDP("rdp", "RDP"),
    SSH("ssh", "SSH"),
    VNC("vnc", "VNC"),
    WEB("web", "Web"),
    PVE("pve", "Console Proxmox"),
    OTHER("other", "Autre");

    /** Vrai si l'app ouvre une session native (via guacd ; consoles Proxmox relayées par le serveur). */
    val opensNatively: Boolean get() = this == SSH || this == RDP || this == VNC || this == PVE

    /** Vrai si le serveur surveille la disponibilité de ce type (port TCP). */
    val isMonitored: Boolean get() = this == SSH || this == RDP || this == VNC

    companion object {
        /** Types qu'on peut créer depuis l'app. */
        val creatable = listOf(SSH, RDP, VNC, WEB)
    }
}

object EntryKindSerializer : LenientEnumSerializer<EntryKind>("EntryKind", EntryKind.entries.toTypedArray(), EntryKind.OTHER)

/** Comparaison alphabétique insensible à la casse, adaptée au français. */
object Sorting {
    fun collator(): Collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.SECONDARY }

    fun <T> byName(name: (T) -> String): Comparator<T> {
        val c = collator()
        return Comparator { a, b -> c.compare(name(a), name(b)) }
    }
}

@Serializable
data class Folder(
    val id: String,
    val parentId: String? = null,
    val name: String,
    /** Identifiant par défaut hérité par les entrées du dossier, par type (« ssh », « rdp »…). */
    val credentials: Map<String, String?> = emptyMap(),
)

@Serializable
data class Entry(
    val id: String,
    val folderId: String? = null,
    val name: String,
    val kind: EntryKind,
    val host: String = "",
    val port: Int = 0,
    val url: String = "",
    val username: String = "",
    val credentialId: String? = null,
    val notes: String = "",
    val favorite: Boolean = false,
    val sourceId: String? = null,
    val updatedAt: String = "",
) {
    /** Cible affichée : hôte et port (ou URL pour le web). */
    val target: String
        get() = when {
            kind == EntryKind.WEB -> url
            host.isEmpty() -> ""
            port > 0 -> "$host:$port"
            else -> host
        }

    /** Texte sur lequel porte la recherche. */
    val searchText: String get() = listOf(name, host, username, notes).joinToString(" ").lowercase()
}

@Serializable
data class Tree(val folders: List<Folder> = emptyList(), val entries: List<Entry> = emptyList()) {
    fun folders(parent: String?): List<Folder> = folders.filter { it.parentId == parent }.sortedWith(Sorting.byName { it.name })

    fun entries(folder: String?): List<Entry> = entries.filter { it.folderId == folder }.sortedWith(Sorting.byName { it.name })

    fun folder(id: String?): Folder? = id?.let { fid -> folders.firstOrNull { it.id == fid } }

    fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** Chemin d'un dossier (« Lab › Proxmox »), pour les résultats de recherche. */
    fun path(folderId: String?): List<Folder> {
        val out = ArrayDeque<Folder>()
        var cursor = folder(folderId)
        var guard = 0
        while (cursor != null && guard < 64) {
            out.addFirst(cursor)
            cursor = folder(cursor.parentId)
            guard++
        }
        return out.toList()
    }

    /** Recherche insensible à la casse sur le nom, l'hôte, l'utilisateur et les notes. */
    fun search(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val terms = q.split(" ").filter { it.isNotEmpty() }
        return entries.filter { e -> terms.all { e.searchText.contains(it) } }.sortedWith(Sorting.byName { it.name })
    }

    companion object {
        val EMPTY = Tree()
    }
}

@Serializable
data class Credential(
    val id: String,
    val name: String,
    val username: String = "",
    val domain: String = "",
    val notes: String = "",
    val hasPassword: Boolean = false,
    val hasPrivateKey: Boolean = false,
    val hasPassphrase: Boolean = false,
    val hasTotp: Boolean = false,
) {
    /** « DOMAINE\utilisateur » ou « utilisateur ». */
    val account: String get() = if (domain.isEmpty()) username else "$domain\\$username"
}

/** Champ secret d'un identifiant, tel que l'API `reveal` l'attend. */
enum class SecretField(val raw: String) { PASSWORD("password"), PRIVATE_KEY("privateKey"), PASSPHRASE("passphrase") }

@Serializable
data class TotpCode(val code: String, val remaining: Int, val period: Int)

@Serializable
data class ConnectionTest(
    val address: String = "",
    val ip: String? = null,
    val status: String,
    val durationMs: Double = 0.0,
    val checkedAt: String = "",
) {
    val reachable: Boolean get() = status == "reachable"

    val statusTitle: String
        get() = when (status) {
            "reachable" -> "Port accessible"
            "refused" -> "Connexion refusée"
            "timeout" -> "Délai dépassé"
            "dns_error" -> "Nom introuvable (DNS)"
            "unreachable" -> "Hôte injoignable"
            else -> "Erreur"
        }
}

@Serializable
data class ServerInfo(val user: String, val version: String = "")

@Serializable
data class SessionTicket(val token: String, @SerialName("protocol") val protocol: String)

/** Options d'ouverture de session envoyées à `/api/entries/{id}/connect`. */
@Serializable
data class SessionOptions(
    val width: Int,
    val height: Int,
    val dpi: Int = 96,
    val timezone: String = TimeZone.getDefault().id,
    val record: Boolean = false,
    /** Console Proxmox relayée par guacd (au lieu de noVNC). */
    val native: Boolean = false,
)

/** Connexion rapide : SSH ou RDP sans fiche (credentialId omis s'il est vide). */
@Serializable
data class QuickConnectRequest(
    val kind: EntryKind,
    val host: String,
    val port: Int,
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val credentialId: String? = null,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val timezone: String,
    val record: Boolean,
) {
    companion object {
        fun of(
            kind: EntryKind, host: String, port: Int, username: String = "", domain: String = "", password: String = "",
            credentialId: String? = null, options: SessionOptions,
        ) = QuickConnectRequest(
            kind, host, port, username, domain, password, credentialId,
            options.width, options.height, options.dpi, options.timezone, options.record,
        )
    }
}

@Serializable
data class AuditEvent(
    val id: Long,
    val at: String,
    val user: String = "",
    val action: String = "",
    val target: String = "",
    val detail: String = "",
)

/** Dates RFC 3339 renvoyées par le serveur (avec ou sans fraction de seconde). */
object RelayDate {
    fun parse(text: String): OffsetDateTime? = try {
        OffsetDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Espace de travail : groupe d'entrées ouvertes ensemble (propre à l'utilisateur). */
@Serializable
data class Workspace(val id: String = "", val name: String, val entryIds: List<String> = emptyList())
