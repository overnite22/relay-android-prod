package io.github.overnite22.relay.kit

import java.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Transport synthétique : enregistre les requêtes et renvoie des réponses préparées (« MÉTHODE /chemin »). */
class StubTransport : HttpTransport {
    class Response(val status: Int, val body: String)

    val responses = HashMap<String, Response>()
    val requests = ArrayList<HttpRequest>()

    operator fun set(key: String, response: Response) {
        responses[key] = response
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        synchronized(this) { requests.add(request) }
        val r = responses["${request.method} ${request.path}"] ?: throw IOException("hôte introuvable")
        return HttpResponse(r.status, r.body.toByteArray())
    }

    val last: HttpRequest get() = requests.last()
}

fun ok(body: String) = StubTransport.Response(200, body)
fun status(code: Int, body: String = "") = StubTransport.Response(code, body)

fun HttpRequest.json(): JsonObject =
    if (body == null) JsonObject(emptyMap()) else RelayJson.default.parseToJsonElement(bodyText).jsonObject

fun JsonElement?.isJsonNull() = this is kotlinx.serialization.json.JsonNull

val account = ServerAccount("https://relay.example.com", "rlt_" + "b".repeat(43))
