package io.github.overnite22.relay.kit

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Requête HTTP indépendante de la bibliothèque réseau (tests et faux serveur de démonstration). */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
) {
    private val uri get() = java.net.URI(url)

    /** Chemin tel qu'envoyé (encodé). */
    val path: String get() = uri.rawPath ?: ""

    /** Requête telle qu'envoyée (encodée), ou null. */
    val query: String? get() = uri.rawQuery

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""
}

class HttpResponse(val status: Int, val body: ByteArray)

/** Transport HTTP injectable (OkHttp en production, réponses synthétiques dans les tests). */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Client OkHttp partagé : sans cookies, sans cache, sans redirection (le jeton ne part jamais vers un autre hôte). */
object RelayHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

class OkHttpTransport(private val client: OkHttpClient = RelayHttp.client) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val body = request.body?.toRequestBody(request.header("Content-Type")?.toMediaTypeOrNull())
            ?: if (request.method == "POST" || request.method == "PUT") ByteArray(0).toRequestBody(null) else null
        val call = client.newCall(
            Request.Builder().url(request.url).headers(request.headers.toHeaders()).method(request.method, body).build(),
        )
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use { it.body.bytes() }
                        cont.resume(HttpResponse(response.code, bytes))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
    }
}

/** Encodage d'URL strict (RFC 3986, espace en %20). */
object UrlEscape {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val PATH_EXTRA = "!$&'()*+,;=:@"

    fun pathSegment(s: String): String = encode(s, UNRESERVED + PATH_EXTRA)

    fun queryValue(s: String): String = encode(s, UNRESERVED)

    private fun encode(s: String, allowed: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && allowed.indexOf(c) >= 0) out.append(c) else out.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return out.toString()
    }
}
