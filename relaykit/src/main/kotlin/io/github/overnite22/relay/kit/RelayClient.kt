package io.github.overnite22.relay.kit

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

sealed class RelayError(message: String) : Exception(message) {
    data object Unauthorized :
        RelayError("Appareil non autorisé : le jeton a été révoqué ou le serveur demande une connexion SSO.")
    data class Forbidden(val detail: String) : RelayError(detail.ifEmpty { "Accès refusé." })
    data object NotFound : RelayError("Introuvable sur le serveur.")
    data class Server(val detail: String) : RelayError(detail)
    data class Transport(val detail: String) : RelayError("Serveur injoignable : $detail")
    data object InvalidResponse : RelayError("Réponse inattendue du serveur.")
}

/** Client de l'API Relay, authentifié par jeton d'appareil (Authorization: Bearer rlt_…). */
class RelayClient(val account: ServerAccount, private val transport: HttpTransport = OkHttpTransport()) {

    // region Requêtes

    fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String = "application/json",
        accept: String = "application/json",
        timeoutMs: Long = 30_000,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpRequest {
        val q = if (query.isEmpty()) "" else "?" + query.joinToString("&") { "${UrlEscape.queryValue(it.first)}=${UrlEscape.queryValue(it.second)}" }
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer ${account.token}"
        headers["X-Relay"] = "1"
        headers["Accept"] = accept
        if (body != null) headers["Content-Type"] = contentType
        headers.putAll(extraHeaders)
        return HttpRequest(method, account.server + path + q, headers, body, timeoutMs)
    }

    internal suspend fun send(request: HttpRequest): ByteArray {
        val response = try {
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RelayError.Transport(e.message ?: e.javaClass.simpleName)
        }
        return when (response.status) {
            in 200..299 -> response.body
            401 -> throw RelayError.Unauthorized
            403 -> throw RelayError.Forbidden(message(response.body))
            404 -> throw RelayError.NotFound
            else -> throw RelayError.Server(message(response.body, "Erreur serveur (HTTP ${response.status})."))
        }
    }

    private fun message(data: ByteArray, fallback: String = ""): String = try {
        val obj = RelayJson.default.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
        (obj?.get("error") as? JsonPrimitive)?.content ?: fallback
    } catch (_: Exception) {
        fallback
    }

    internal fun <T> decode(serializer: KSerializer<T>, data: ByteArray): T = try {
        RelayJson.default.decodeFromString(serializer, data.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        throw RelayError.InvalidResponse
    }

    internal suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        decode(serializer<T>(), send(request("GET", path, query)))

    internal suspend inline fun <reified T, reified B> post(path: String, body: B, json: Json = RelayJson.default): T =
        decode(serializer<T>(), send(request("POST", path, body = json.encodeToString(serializer<B>(), body).toByteArray())))

    internal suspend inline fun <reified T, reified B> put(path: String, body: B, json: Json = RelayJson.default): T =
        decode(serializer<T>(), send(request("PUT", path, body = json.encodeToString(serializer<B>(), body).toByteArray())))

    internal suspend fun sendEmpty(method: String, path: String, body: ByteArray? = null) {
        send(request(method, path, body = body))
    }

    internal fun esc(id: String): String = UrlEscape.pathSegment(id)

    // endregion

    // region API

    suspend fun me(): ServerInfo = get("/api/me")

    suspend fun tree(): Tree = get("/api/tree")

    suspend fun connectionStatus(): Map<String, ConnectionTest> = get("/api/connection-status")

    suspend fun entry(id: String): Entry = get("/api/entries/${esc(id)}")

    suspend fun testConnection(id: String): ConnectionTest =
        decode(ConnectionTest.serializer(), send(request("POST", "/api/entries/${esc(id)}/test-connection")))

    suspend fun connect(id: String, options: SessionOptions): SessionTicket = post("/api/entries/${esc(id)}/connect", options)

    suspend fun quickConnect(req: QuickConnectRequest): SessionTicket = post("/api/quick-connect", req, RelayJson.omitNulls)

    suspend fun credentials(): List<Credential> = get("/api/credentials")

    @Serializable
    private data class RevealBody(val field: String)

    @Serializable
    private data class RevealValue(val value: String)

    suspend fun reveal(id: String, field: SecretField): String =
        post<RevealValue, RevealBody>("/api/credentials/${esc(id)}/reveal", RevealBody(field.raw)).value

    suspend fun totp(id: String): TotpCode = get("/api/credentials/${esc(id)}/totp")

    suspend fun audit(limit: Int = 200): List<AuditEvent> = get("/api/audit", listOf("limit" to limit.toString()))

    suspend fun workspaces(): List<Workspace> = get("/api/workspaces")

    suspend fun saveWorkspace(w: Workspace): Workspace =
        if (w.id.isEmpty()) post("/api/workspaces", w) else put("/api/workspaces/${esc(w.id)}", w)

    suspend fun deleteWorkspace(id: String) = sendEmpty("DELETE", "/api/workspaces/${esc(id)}")

    suspend fun recordings(): List<Recording> = get<List<Recording>>("/api/recordings").sortedByDescending { it.at }

    /** Contenu brut d'un enregistrement (flux Guacamole, 64 Mio au plus). */
    suspend fun recordingData(id: String): ByteArray =
        send(request("GET", "/api/recordings/${esc(id)}", accept = "application/octet-stream", timeoutMs = 120_000))

    suspend fun deleteRecording(id: String) = sendEmpty("DELETE", "/api/recordings/${esc(id)}")

    /** Met à jour le favori d'une entrée (PUT complet, réglages avancés conservés comme dans Relay web). */
    suspend fun setFavorite(entry: Entry, favorite: Boolean): Entry {
        val raw: JsonObject = get("/api/entries/${esc(entry.id)}")
        val obj = raw.toMutableMap()
        obj["favorite"] = JsonPrimitive(favorite)
        for (key in listOf("id", "createdAt", "updatedAt", "sourceId", "externalId")) obj.remove(key)
        return put("/api/entries/${esc(entry.id)}", JsonObject(obj))
    }

    /** URL WebSocket du tunnel Guacamole pour un jeton de session. */
    fun tunnelUrl(token: String): String {
        val ws = if (account.server.startsWith("http://")) "ws://" + account.server.removePrefix("http://")
        else "wss://" + account.server.removePrefix("https://")
        return "$ws/api/tunnel?token=${UrlEscape.queryValue(token)}"
    }

    // endregion

    // region Corps bruts (fichiers)

    internal suspend inline fun <reified T> sendRaw(
        method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: ByteArray, contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): T = decode(
        serializer<T>(),
        send(request(method, path, query, body, contentType, timeoutMs = 300_000, extraHeaders = extraHeaders)),
    )

    internal suspend fun getRaw(path: String, method: String = "GET", body: ByteArray? = null): ByteArray =
        send(request(method, path, body = body, accept = "*/*", timeoutMs = 300_000))

    internal fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // endregion

    companion object {
        /** Sérialiseurs utiles aux tests et à l'app. */
        val stringMap: KSerializer<Map<String, String>> = MapSerializer(String.serializer(), String.serializer())
        val elementList: KSerializer<List<JsonElement>> = ListSerializer(JsonElement.serializer())
    }
}
