package io.github.overnite22.relay.kit

import kotlinx.serialization.Serializable

@Serializable(with = RunStatusSerializer::class)
enum class RunStatus(override val raw: String, val title: String) : RawValued {
    PENDING("pending", "En attente"),
    RUNNING("running", "En cours"),
    OK("ok", "Réussie"),
    FAILED("failed", "Échec"),
    ERROR("error", "Erreur"),
    CANCELED("canceled", "Annulée"),
    UNKNOWN("unknown", "Inconnu");

    val isFinal: Boolean get() = this != PENDING && this != RUNNING
}

object RunStatusSerializer : LenientEnumSerializer<RunStatus>("RunStatus", RunStatus.entries.toTypedArray(), RunStatus.UNKNOWN)

/** Résultat d'une commande sur un serveur. */
@Serializable
data class RunResult(
    val entryId: String,
    val name: String = "",
    val address: String = "",
    val status: RunStatus,
    val exitCode: Int? = null,
    val output: String = "",
    val truncated: Boolean = false,
    val error: String = "",
    val durationMs: Double = 0.0,
    val fingerprint: String = "",
    val newHostKey: Boolean = false,
) {
    val id: String get() = entryId

    /** Vrai si la clé du serveur a changé : la commande a été refusée par précaution. */
    val hostKeyChanged: Boolean get() = status == RunStatus.ERROR && error.contains("clé", ignoreCase = true)
}

/** Exécution d'une commande sur plusieurs serveurs (suivie, puis conservée dans l'historique). */
@Serializable
data class CommandRun(
    val id: String,
    val at: String,
    val finishedAt: String? = null,
    val user: String = "",
    val command: String,
    val sudo: Boolean = false,
    val targets: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val results: List<RunResult>? = null,
) {
    val finished: Boolean get() = finishedAt != null
}

@Serializable
data class SavedCommand(val id: String = "", val name: String, val command: String, val sudo: Boolean = false)

@Serializable
data class RunRequest(
    val entryIds: List<String>,
    val command: String,
    val sudo: Boolean,
    val timeoutSec: Int = 30,
    val concurrency: Int = 10,
)

@Serializable
private data class SavedCommandBody(val name: String, val command: String, val sudo: Boolean)

@Serializable
private data class AddressBody(val address: String)

suspend fun RelayClient.startRun(request: RunRequest): CommandRun = post("/api/commands/run", request)

suspend fun RelayClient.run(id: String): CommandRun = get("/api/commands/runs/${esc(id)}")

suspend fun RelayClient.cancelRun(id: String) = sendEmpty("POST", "/api/commands/runs/${esc(id)}/cancel", "{}".toByteArray())

suspend fun RelayClient.runHistory(limit: Int = 50): List<CommandRun> =
    get("/api/commands/runs", listOf("limit" to limit.toString()))

suspend fun RelayClient.savedCommands(): List<SavedCommand> = get("/api/commands/saved")

suspend fun RelayClient.saveCommand(command: SavedCommand): SavedCommand {
    val body = SavedCommandBody(command.name, command.command, command.sudo)
    return if (command.id.isEmpty()) post("/api/commands/saved", body) else put("/api/commands/saved/${esc(command.id)}", body)
}

suspend fun RelayClient.deleteSavedCommand(id: String) = sendEmpty("DELETE", "/api/commands/saved/${esc(id)}")

/** Oublie la clé SSH mémorisée d'un serveur (après un changement de clé légitime). */
suspend fun RelayClient.forgetHostKey(address: String) =
    sendEmpty("POST", "/api/known-hosts/forget", RelayJson.default.encodeToString(AddressBody.serializer(), AddressBody(address)).toByteArray())
