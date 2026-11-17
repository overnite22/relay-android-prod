package io.github.overnite22.relay.kit

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlinx.serialization.Serializable

/** Compte enregistré (chiffré par le Keystore côté app) : serveur Relay et jeton d'appareil. */
@Serializable
data class ServerAccount(
    /** Origine normalisée, sans chemin ni barre finale (https://relay.example.com). */
    val server: String,
    val token: String,
) {
    val serverDisplay: String get() = runCatching { URI(server).host }.getOrNull() ?: server
}

sealed class PairingError(message: String) : Exception(message) {
    data object NotPairingCode : PairingError("Ce QR code n'est pas un code d'appairage Relay.")
    data object InvalidServer : PairingError("Adresse du serveur invalide (ex. https://relay.example.com).")
    data object InsecureServer : PairingError("Le serveur doit être en HTTPS.")
    data object InvalidToken : PairingError("Jeton invalide : il commence par « rlt_ ».")
}

/** Lecture du QR code « relay://pair?server=…&token=… » et validation de la saisie manuelle. */
object Pairing {
    const val TOKEN_PREFIX = "rlt_"

    fun parse(code: String): ServerAccount {
        val uri = try {
            URI(code.trim())
        } catch (_: URISyntaxException) {
            throw PairingError.NotPairingCode
        }
        if (uri.scheme != "relay" || uri.host != "pair") throw PairingError.NotPairingCode
        val items = queryItems(uri.rawQuery)
        return account(items["server"] ?: "", items["token"] ?: "")
    }

    fun account(server: String, token: String): ServerAccount {
        val url = serverUrl(server)
        val tok = token.trim()
        val valid = tok.startsWith(TOKEN_PREFIX) && tok.length in 36..160 && tok.all { it.code in 33..126 }
        if (!valid) throw PairingError.InvalidToken
        return ServerAccount(url, tok)
    }

    /** Normalise l'adresse : https imposé (http toléré pour localhost), pas de chemin ni d'identifiants. */
    fun serverUrl(text: String): String {
        var raw = text.trim()
        if (raw.isEmpty()) throw PairingError.InvalidServer
        if (!raw.contains("://")) raw = "https://$raw"
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw PairingError.InvalidServer
        }
        val host = uri.host
        if (host.isNullOrEmpty() || uri.rawUserInfo != null) throw PairingError.InvalidServer
        val local = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (!local) throw PairingError.InsecureServer
            else -> throw PairingError.InvalidServer
        }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://$host$port"
    }

    private fun queryItems(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
            out.putIfAbsent(key, value)
        }
        return out
    }

    // « + » reste un « + » (comme URLComponents), seuls les %XX sont décodés.
    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
}
