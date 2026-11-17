package io.github.overnite22.relay.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Fiche complète éditable (réglages avancés conservés tels quels). Tous les champs sont envoyés, null compris. */
@Serializable
data class EntryInput(
    val folderId: String? = null,
    val name: String = "",
    val kind: EntryKind = EntryKind.SSH,
    val host: String = "",
    val port: Int = 22,
    val url: String = "",
    val username: String = "",
    val credentialId: String? = null,
    val settings: Map<String, JsonElement> = emptyMap(),
    val notes: String = "",
    val favorite: Boolean = false,
) {
    companion object {
        /** Port par défaut d'un type. */
        fun defaultPort(kind: EntryKind): Int = when (kind) {
            EntryKind.SSH -> 22
            EntryKind.RDP -> 3389
            EntryKind.VNC -> 5900
            else -> 0
        }
    }
}

/** Identifiant du coffre à créer ou modifier. null = secret inchangé (non envoyé), "" = secret effacé. */
@Serializable
data class CredentialInput(
    val name: String = "",
    val username: String = "",
    val domain: String = "",
    val notes: String = "",
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val totp: String? = null,
)

@Serializable
data class Attachment(val id: String, val name: String, val size: Long = 0, val createdAt: String = "")

/** Source de synchronisation (Proxmox ou NetBox). */
@Serializable
data class SyncSource(
    val id: String,
    val kind: String,
    val name: String,
    val url: String = "",
    val tokenId: String = "",
    val hasSecret: Boolean = false,
    val verifyTls: Boolean = true,
    val folderId: String? = null,
    val options: Map<String, JsonElement> = emptyMap(),
    val intervalMin: Int = 0,
    val lastSyncAt: String? = null,
    val lastStatus: String = "",
) {
    val kindTitle: String get() = when (kind) {
        "proxmox" -> "Proxmox VE"
        "netbox" -> "NetBox"
        else -> kind
    }
}

/** Source à créer ou modifier ; secret null = inchangé (non envoyé). */
@Serializable
data class SyncSourceInput(
    val kind: String,
    val name: String = "",
    val url: String = "",
    val tokenId: String = "",
    val secret: String? = null,
    val verifyTls: Boolean = true,
    val options: Map<String, JsonElement> = emptyMap(),
    val intervalMin: Int = 0,
) {
    companion object {
        fun of(s: SyncSource) = SyncSourceInput(s.kind, s.name, s.url, s.tokenId, null, s.verifyTls, s.options, s.intervalMin)
    }
}

@Serializable
data class SourceResult(val ok: Boolean, val message: String = "")

@Serializable
data class DuplicateGroup(val key: String, val label: String, val entries: List<Entry> = emptyList()) {
    val id: String get() = key
}

@Serializable
data class ImportPlan(
    val folders: List<PlanFolder> = emptyList(),
    val entries: List<PlanEntry> = emptyList(),
    val credentials: List<PlanCredential> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    @Serializable
    data class PlanFolder(val path: List<String> = emptyList())

    @Serializable
    data class PlanEntry(
        val name: String,
        val kind: EntryKind,
        val host: String = "",
        val port: Int = 0,
        val path: List<String> = emptyList(),
        val credential: String = "",
        val inlineSecret: Boolean = false,
    )

    @Serializable
    data class PlanCredential(val name: String, val username: String = "", val encryptedSecret: Boolean = false)

    @Serializable
    data class Skipped(val name: String, val type: String = "", val reason: String = "")
}

@Serializable
data class ImportResult(val folders: Int, val entries: Int, val credentials: Int, val rootId: String = "")

@Serializable
private data class FolderBody(val name: String, val parentId: String?)

@Serializable
private data class FolderCredentialsBody(val credentials: Map<String, String?>)

@Serializable
private data class IdsBody(val ids: List<String>)

@Serializable
private data class PasswordBody(val password: String)

@Serializable
private data class MessageReply(val message: String = "")

// region Entrées et dossiers

/** Fiche complète (avec réglages avancés) pour l'édition. */
suspend fun RelayClient.entryInput(id: String): EntryInput = get("/api/entries/${esc(id)}")

suspend fun RelayClient.saveEntry(input: EntryInput, id: String? = null): Entry =
    if (id != null) put("/api/entries/${esc(id)}", input) else post("/api/entries", input)

suspend fun RelayClient.deleteEntry(id: String) = sendEmpty("DELETE", "/api/entries/${esc(id)}")

suspend fun RelayClient.saveFolder(name: String, parentId: String?, id: String? = null): Folder {
    val body = FolderBody(name, parentId)
    return if (id != null) put("/api/folders/${esc(id)}", body) else post("/api/folders", body)
}

suspend fun RelayClient.deleteFolder(id: String) = sendEmpty("DELETE", "/api/folders/${esc(id)}")

/** Identifiants hérités par type d'entrée (null = aucun). */
suspend fun RelayClient.setFolderCredentials(id: String, credentials: Map<String, String?>) = sendEmpty(
    "PUT", "/api/folders/${esc(id)}/credentials",
    RelayJson.default.encodeToString(FolderCredentialsBody.serializer(), FolderCredentialsBody(credentials)).toByteArray(),
)

// endregion

// region Coffre

suspend fun RelayClient.saveCredential(input: CredentialInput, id: String? = null): Credential =
    if (id != null) put("/api/credentials/${esc(id)}", input, RelayJson.omitNulls)
    else post("/api/credentials", input, RelayJson.omitNulls)

suspend fun RelayClient.deleteCredential(id: String) = sendEmpty("DELETE", "/api/credentials/${esc(id)}")

// endregion

// region Pièces jointes

suspend fun RelayClient.attachments(entryId: String): List<Attachment> = get("/api/entries/${esc(entryId)}/attachments")

suspend fun RelayClient.addAttachment(entryId: String, name: String, data: ByteArray): Attachment =
    sendRaw("POST", "/api/entries/${esc(entryId)}/attachments", listOf("name" to name), data, "application/octet-stream")

suspend fun RelayClient.attachmentData(entryId: String, id: String): ByteArray =
    getRaw("/api/entries/${esc(entryId)}/attachments/${esc(id)}")

suspend fun RelayClient.deleteAttachment(entryId: String, id: String) =
    sendEmpty("DELETE", "/api/entries/${esc(entryId)}/attachments/${esc(id)}")

// endregion

// region Synchronisation

suspend fun RelayClient.sources(): List<SyncSource> = get("/api/sources")

suspend fun RelayClient.saveSource(input: SyncSourceInput, id: String? = null): SyncSource =
    if (id != null) put("/api/sources/${esc(id)}", input, RelayJson.omitNulls)
    else post("/api/sources", input, RelayJson.omitNulls)

suspend fun RelayClient.deleteSource(id: String) = sendEmpty("DELETE", "/api/sources/${esc(id)}")

suspend fun RelayClient.testSource(id: String): SourceResult = post("/api/sources/${esc(id)}/test", JsonObject(emptyMap()))

suspend fun RelayClient.syncSource(id: String): SourceResult = post("/api/sources/${esc(id)}/sync", JsonObject(emptyMap()))

suspend fun RelayClient.clearExclusions(sourceId: String): String =
    post<MessageReply, JsonObject>("/api/sources/${esc(sourceId)}/clear-exclusions", JsonObject(emptyMap())).message

// endregion

// region Doublons

suspend fun RelayClient.duplicates(): List<DuplicateGroup> = get("/api/duplicates")

suspend fun RelayClient.merge(keep: String, remove: List<String>): Entry = post("/api/entries/${esc(keep)}/merge", IdsBody(remove))

// endregion

// region Import RDM

suspend fun RelayClient.previewImport(file: ByteArray): ImportPlan =
    sendRaw("POST", "/api/import/rdm/preview", body = file, contentType = "application/xml")

suspend fun RelayClient.applyImport(file: ByteArray, folder: String, sshDefault: String, rdpDefault: String): ImportResult =
    sendRaw(
        "POST", "/api/import/rdm/apply",
        listOf("folder" to folder, "sshDefault" to sshDefault, "rdpDefault" to rdpDefault),
        file, "application/xml",
    )

// endregion

// region Sauvegarde

/** Archive chiffrée de toutes les données (à conserver hors du serveur). */
suspend fun RelayClient.exportBackup(password: String): ByteArray =
    getRaw("/api/backups/export", "POST", RelayJson.default.encodeToString(PasswordBody.serializer(), PasswordBody(password)).toByteArray())

/** Remplace TOUTES les données du serveur par celles de l'archive. */
suspend fun RelayClient.restoreBackup(archive: ByteArray, password: String) {
    sendRaw<JsonElement>(
        "POST", "/api/backups/restore", body = archive, contentType = "application/octet-stream",
        extraHeaders = mapOf("X-Backup-Password" to base64(password)),
    )
}

// endregion
