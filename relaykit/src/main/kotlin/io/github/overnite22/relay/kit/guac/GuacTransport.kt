package io.github.overnite22.relay.kit.guac

import io.github.overnite22.relay.kit.RelayHttp
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Transport d'un tunnel Guacamole : WebSocket en production, flux scripté pour les tests.
 * Les rappels arrivent sur un fil d'arrière-plan : l'appelant les ramène sur le fil principal.
 */
interface GuacTransport {
    var onText: ((String) -> Unit)?
    /** Fermeture du tunnel ; raison lisible en cas d'erreur réseau, null sinon. */
    var onClose: ((String?) -> Unit)?
    fun start()
    fun send(text: String)
    fun close()
}

/** Tunnel WebSocket Relay (`/api/tunnel?token=…`), authentifié par le jeton d'appareil. */
class RelayTunnelTransport(
    private val url: String,
    private val deviceToken: String,
    private val client: OkHttpClient = RelayHttp.client,
) : GuacTransport {
    override var onText: ((String) -> Unit)? = null
    override var onClose: ((String?) -> Unit)? = null

    @Volatile private var socket: WebSocket? = null
    private val closed = AtomicBoolean(false)

    override fun start() {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $deviceToken")
            .header("X-Relay", "1")
            .header("Sec-WebSocket-Protocol", "guacamole")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onText?.invoke(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onText?.invoke(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) {
                    null -> t.message ?: "Connexion perdue"
                    401 -> "Appareil non autorisé"
                    403 -> "Accès refusé"
                    else -> "Tunnel refusé (HTTP ${response.code})"
                }
                finish(why)
            }
        })
    }

    override fun send(text: String) {
        if (socket?.send(text) == false) finish("Envoi impossible")
    }

    override fun close() {
        val already = closed.getAndSet(true)
        socket?.close(1000, null)
        socket?.cancel()
        if (!already) onClose?.invoke(null)
    }

    private fun finish(reason: String?) {
        if (closed.getAndSet(true)) return
        socket?.cancel()
        onClose?.invoke(reason)
    }
}

/** Flux scripté : rejoue des instructions, enregistre les envois (tests, démonstration). */
class ScriptedTransport(private val script: List<String>) : GuacTransport {
    override var onText: ((String) -> Unit)? = null
    override var onClose: ((String?) -> Unit)? = null
    /** Réponses d'un faux serveur aux envois du client (tests de fichiers). */
    var respond: ((String) -> List<String>)? = null
    private val sentLog = Collections.synchronizedList(ArrayList<String>())
    // Un seul fil : le script puis les réponses arrivent dans l'ordre.
    private val queue = Executors.newSingleThreadExecutor { r -> Thread(r, "scripted-guac").apply { isDaemon = true } }

    val sent: List<String> get() = synchronized(sentLog) { sentLog.toList() }

    override fun start() {
        val deliver = onText
        queue.execute { for (chunk in script) deliver?.invoke(chunk) }
    }

    override fun send(text: String) {
        sentLog.add(text)
        val replies = respond?.invoke(text).orEmpty()
        if (replies.isEmpty()) return
        val deliver = onText
        queue.execute { for (r in replies) deliver?.invoke(r) }
    }

    override fun close() {
        onClose?.invoke(null)
    }
}
