package io.github.overnite22.relay.kit.guac

import java.io.ByteArrayOutputStream
import java.util.Base64

enum class GuacState { IDLE, CONNECTING, WAITING, CONNECTED, DISCONNECTING, DISCONNECTED }

data class GuacError(override val message: String, val status: Int) : Exception(message) {
    /** Libellé lisible d'après le code d'état Guacamole. */
    val description: String
        get() = when (status) {
            0x0200 -> "Le serveur guacd ne peut pas ouvrir la session : $message"
            0x0201 -> "Service occupé, réessayez : $message"
            0x0202 -> "Délai dépassé côté serveur : $message"
            0x0203 -> "Erreur du serveur distant : $message"
            0x0301 -> "Non autorisé : $message"
            0x0303 -> "Accès refusé : $message"
            0x0308 -> "Session fermée pour inactivité."
            0x031D -> "Trop de sessions ouvertes."
            else -> message.ifEmpty { "Session interrompue (code $status)." }
        }
}

/** Fichier poussé par le serveur (RDP : copie dans le dossier Download du lecteur). */
data class ReceivedFile(val name: String, val mimetype: String, val data: Bytes)

/** Contenu d'un objet du système de fichiers (dossier : index JSON ; fichier : octets). */
data class RemoteObject(val mimetype: String, val data: Bytes)

/**
 * Cœur du client Guacamole : interprète les instructions reçues, produit des commandes d'affichage
 * et les instructions à renvoyer. Indépendant de l'UI ; à utiliser depuis un seul fil (fil principal).
 */
class GuacClientCore {
    var state: GuacState = GuacState.IDLE
        private set
    var tunnelId: String? = null
        private set
    var name: String? = null
        private set

    var onDisplay: ((DisplayCommand) -> Unit)? = null
    var onState: ((GuacState) -> Unit)? = null
    var onName: ((String) -> Unit)? = null
    var onClipboard: ((String) -> Unit)? = null
    var onError: ((GuacError) -> Unit)? = null
    /** Système de fichiers proposé par guacd (SFTP en SSH, lecteur en RDP) : index d'objet et nom. */
    var onFilesystem: ((Int, String) -> Unit)? = null
    /** Fichier poussé par le serveur. */
    var onFileReceived: ((ReceivedFile) -> Unit)? = null
    /** Progression d'un fichier poussé par le serveur (nom, octets reçus). */
    var onFileProgress: ((String, Int) -> Unit)? = null
    /** Texte à écrire sur le tunnel. */
    var onSend: ((String) -> Unit)? = null

    private val parser = GuacParser()
    private val streams = HashMap<Int, Stream>()
    private var nextOutputStream = 0
    private var lastSync = 0

    private sealed class Stream {
        val data = ByteArrayOutputStream()
        class Image(val layer: Int, val mask: Int, val x: Int, val y: Int, val mime: String) : Stream()
        class Clipboard(val mime: String) : Stream()
        class Object(val request: Int, val mime: String) : Stream()
        class File(val name: String, val mime: String) : Stream()
        object Ignored : Stream()
    }

    private class PendingGet(
        val id: Int, val obj: Int, val name: String, val progress: ((Int) -> Unit)?, val completion: (Result<RemoteObject>) -> Unit,
    )

    private class PendingPut(
        val data: ByteArray, var offset: Int, var ended: Boolean, val progress: ((Int) -> Unit)?, val completion: (Result<Unit>) -> Unit,
    )

    private val gets = ArrayList<PendingGet>()
    private val getsByStream = HashMap<Int, PendingGet>()
    private val puts = HashMap<Int, PendingPut>()
    private var nextRequest = 0

    // region Cycle de vie

    fun connecting() = set(GuacState.CONNECTING)

    /** Données reçues du tunnel (une ou plusieurs instructions, éventuellement partielles). */
    fun receive(text: String) {
        val instructions = try {
            parser.feed(text)
        } catch (_: GuacParseException) {
            fail(GuacError("Flux Guacamole illisible", 0x0203))
            return
        }
        for (i in instructions) handle(i)
    }

    /** Le tunnel est fermé (par le serveur ou le réseau). */
    fun tunnelClosed() {
        if (state != GuacState.DISCONNECTED) set(GuacState.DISCONNECTED)
    }

    fun disconnect() {
        if (state == GuacState.DISCONNECTED || state == GuacState.DISCONNECTING) return
        set(GuacState.DISCONNECTING)
        send(GuacInstruction("disconnect"))
        set(GuacState.DISCONNECTED)
    }

    // endregion

    // region Envois

    fun sendMouse(x: Int, y: Int, buttons: Int) {
        if (state != GuacState.CONNECTED) return
        send(GuacInstruction("mouse", x.toString(), y.toString(), buttons.toString()))
    }

    fun sendKey(keysym: Int, pressed: Boolean) {
        if (state != GuacState.CONNECTED || keysym == 0) return
        send(GuacInstruction("key", keysym.toString(), if (pressed) "1" else "0"))
    }

    /** Appui puis relâchement d'une touche. */
    fun tapKey(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    /** Frappe d'un texte, chaque caractère étant converti en keysym. */
    fun type(text: String) {
        for (k in Keysym.sequence(text)) tapKey(k)
    }

    fun sendSize(width: Int, height: Int) {
        if (state != GuacState.CONNECTED && state != GuacState.WAITING) return
        send(GuacInstruction("size", width.toString(), height.toString()))
    }

    /** Envoie le presse-papiers local (texte) au serveur distant. */
    fun sendClipboard(text: String) {
        if (state != GuacState.CONNECTED) return
        val index = nextOutputStream++
        send(GuacInstruction("clipboard", index.toString(), "text/plain"))
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + BLOB_SIZE, bytes.size)
            send(GuacInstruction("blob", index.toString(), b64(bytes.copyOfRange(offset, end))))
            offset = end
        }
        send(GuacInstruction("end", index.toString()))
    }

    // endregion

    // region Fichiers (objets Guacamole)

    /** Demande le contenu d'un chemin de l'objet (dossier : index JSON ; fichier : octets). */
    fun requestObject(obj: Int, name: String, progress: ((Int) -> Unit)? = null, completion: (Result<RemoteObject>) -> Unit) {
        if (state != GuacState.CONNECTED) {
            completion(Result.failure(GuacError("Session non connectée", 0x0300)))
            return
        }
        nextRequest++
        gets.add(PendingGet(nextRequest, obj, name, progress, completion))
        send(GuacInstruction("get", obj.toString(), name))
    }

    /** Envoie un fichier vers [name] dans l'objet ; chaque bloc attend l'accusé du serveur. */
    fun putObject(
        obj: Int, name: String, mimetype: String, data: ByteArray, progress: ((Int) -> Unit)? = null,
        completion: (Result<Unit>) -> Unit,
    ) {
        if (state != GuacState.CONNECTED) {
            completion(Result.failure(GuacError("Session non connectée", 0x0300)))
            return
        }
        val index = nextOutputStream++
        puts[index] = PendingPut(data, 0, false, progress, completion)
        send(GuacInstruction("put", obj.toString(), index.toString(), mimetype, name))
    }

    /** Abandonne les transferts en cours (session fermée). */
    private fun failTransfers(error: GuacError) {
        val pendingGets = gets + getsByStream.values
        val pendingPuts = puts.values.toList()
        gets.clear()
        getsByStream.clear()
        puts.clear()
        for (g in pendingGets) g.completion(Result.failure(error))
        for (p in pendingPuts) p.completion(Result.failure(error))
    }

    private fun handleAck(stream: Int, message: String, status: Int) {
        val put = puts[stream] ?: return
        if (status != 0) {
            puts.remove(stream)
            put.completion(Result.failure(GuacError(message.ifEmpty { "Envoi refusé" }, status)))
            return
        }
        if (put.ended) {
            puts.remove(stream)
            put.completion(Result.success(Unit))
            return
        }
        if (put.offset >= put.data.size) {
            put.ended = true
            send(GuacInstruction("end", stream.toString()))
            // guacd n'accuse pas toujours la fin : le transfert est terminé côté client.
            puts.remove(stream)
            put.completion(Result.success(Unit))
            return
        }
        val end = minOf(put.offset + BLOB_SIZE, put.data.size)
        val chunk = put.data.copyOfRange(put.offset, end)
        put.offset = end
        send(GuacInstruction("blob", stream.toString(), b64(chunk)))
        put.progress?.invoke(end)
    }

    // endregion

    /** Instruction de maintien du tunnel (toutes les 5 s, comme le client web). */
    fun ping() = send(GuacInstruction("", "ping", System.currentTimeMillis().toString()))

    /** À appeler une fois la trame `sync` rendue à l'écran. */
    fun acknowledgeSync(timestamp: Int) = send(GuacInstruction("sync", timestamp.toString()))

    // region Réception

    private fun handle(i: GuacInstruction) {
        when (i.opcode) {
            "" -> {
                // Messages internes du tunnel : identifiant, ou écho d'un ping.
                val first = i.string(0)
                if (first == "ping" || first == "relay-error") return
                tunnelId = first
                if (state == GuacState.CONNECTING) set(GuacState.WAITING)
            }
            "sync" -> {
                lastSync = i.int(0)
                if (state == GuacState.WAITING || state == GuacState.CONNECTING) set(GuacState.CONNECTED)
                onDisplay?.invoke(DisplayCommand.Sync(lastSync))
            }
            "nop", "ready", "required", "msg", "set" -> Unit
            "argv" -> ack(i.int(0), "Unsupported", 0x0100)
            "filesystem" -> onFilesystem?.invoke(i.int(0), i.string(1))
            "body" -> {
                // body,<objet>,<flux>,<type>,<nom> : réponse à un get.
                val obj = i.int(0)
                val stream = i.int(1)
                val name = i.string(3)
                val index = gets.indexOfFirst { it.obj == obj && it.name == name }
                if (index < 0) {
                    ack(stream, "Unexpected", 0x0100)
                    return
                }
                val request = gets.removeAt(index)
                getsByStream[stream] = request
                streams[stream] = Stream.Object(request.id, i.string(2))
                ack(stream, "OK", 0)
            }
            "ack" -> handleAck(i.int(0), i.string(1), i.int(2))
            "error" -> fail(GuacError(i.string(0), i.int(1)))
            "disconnect" -> set(GuacState.DISCONNECTED)
            "name" -> {
                name = i.string(0)
                onName?.invoke(i.string(0))
            }
            "size" -> display(DisplayCommand.Size(i.int(0), i.int(1), i.int(2)))
            "img" -> streams[i.int(0)] = Stream.Image(i.int(2), i.int(1), i.int(4), i.int(5), i.string(3))
            "png", "jpeg", "webp" -> {
                val mime = "image/" + i.opcode
                display(DisplayCommand.Image(i.int(1), i.int(0), i.int(2), i.int(3), mime, Bytes(unb64(i.string(4)))))
            }
            "blob" -> {
                val index = i.int(0)
                val stream = streams[index] ?: return
                val chunk = unb64(i.string(1))
                if (stream !is Stream.Ignored) stream.data.write(chunk)
                when (stream) {
                    is Stream.Object -> getsByStream[index]?.progress?.invoke(stream.data.size())
                    is Stream.File -> onFileProgress?.invoke(stream.name, stream.data.size())
                    else -> Unit
                }
                ack(index, "OK", 0)
            }
            "end" -> {
                val index = i.int(0)
                when (val stream = streams.remove(index) ?: return) {
                    is Stream.Image -> display(
                        DisplayCommand.Image(stream.layer, stream.mask, stream.x, stream.y, stream.mime, Bytes(stream.data.toByteArray())),
                    )
                    is Stream.Clipboard -> if (stream.mime.startsWith("text/")) {
                        onClipboard?.invoke(stream.data.toByteArray().toString(Charsets.UTF_8))
                    }
                    is Stream.Object -> getsByStream.remove(index)?.completion?.invoke(
                        Result.success(RemoteObject(stream.mime, Bytes(stream.data.toByteArray()))),
                    )
                    is Stream.File -> onFileReceived?.invoke(ReceivedFile(stream.name, stream.mime, Bytes(stream.data.toByteArray())))
                    Stream.Ignored -> Unit
                }
            }
            "clipboard" -> streams[i.int(0)] = Stream.Clipboard(i.string(1))
            "file" -> {
                // file,<flux>,<type>,<nom> : téléchargement poussé par le serveur.
                streams[i.int(0)] = Stream.File(i.string(2), i.string(1))
                ack(i.int(0), "OK", 0)
            }
            "audio", "video", "pipe" -> {
                streams[i.int(0)] = Stream.Ignored
                ack(i.int(0), "Unsupported", 0x0100)
            }
            "copy" -> display(DisplayCommand.Copy(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4), i.int(5), i.int(6), i.int(7), i.int(8)))
            "transfer" -> display(DisplayCommand.Transfer(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4), i.int(5), i.int(6), i.int(7), i.int(8)))
            "rect" -> display(DisplayCommand.Rect(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4)))
            "start" -> display(DisplayCommand.Start(i.int(0), i.int(1), i.int(2)))
            "line" -> display(DisplayCommand.Line(i.int(0), i.int(1), i.int(2)))
            "curve" -> display(DisplayCommand.Curve(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4), i.int(5), i.int(6)))
            "arc" -> display(DisplayCommand.Arc(i.int(0), i.int(1), i.int(2), i.int(3), i.double(4), i.double(5), i.int(6) != 0))
            "close" -> display(DisplayCommand.Close(i.int(0)))
            "clip" -> display(DisplayCommand.Clip(i.int(0)))
            "push" -> display(DisplayCommand.Push(i.int(0)))
            "pop" -> display(DisplayCommand.Pop(i.int(0)))
            "reset" -> display(DisplayCommand.Reset(i.int(0)))
            "identity" -> display(DisplayCommand.Identity(i.int(0)))
            "transform" -> display(DisplayCommand.Transform(i.int(0), i.double(1), i.double(2), i.double(3), i.double(4), i.double(5), i.double(6)))
            "distort" -> display(DisplayCommand.Distort(i.int(0), i.double(1), i.double(2), i.double(3), i.double(4), i.double(5), i.double(6)))
            "cfill" -> display(DisplayCommand.Cfill(i.int(1), i.int(0), i.int(2), i.int(3), i.int(4), i.int(5)))
            "cstroke" -> display(DisplayCommand.Cstroke(i.int(1), i.int(0), i.int(2), i.int(3), i.int(4), i.int(5), i.int(6), i.int(7), i.int(8)))
            "lfill" -> display(DisplayCommand.Lfill(i.int(1), i.int(0), i.int(2)))
            "lstroke" -> display(DisplayCommand.Lstroke(i.int(1), i.int(0), i.int(2), i.int(3), i.int(4), i.int(5)))
            "dispose" -> display(DisplayCommand.Dispose(i.int(0)))
            "move" -> display(DisplayCommand.Move(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4)))
            "shade" -> display(DisplayCommand.Shade(i.int(0), i.int(1)))
            "cursor" -> display(DisplayCommand.Cursor(i.int(0), i.int(1), i.int(2), i.int(3), i.int(4), i.int(5), i.int(6)))
            else -> Unit
        }
    }

    // endregion

    private fun display(c: DisplayCommand) {
        onDisplay?.invoke(c)
    }

    private fun ack(stream: Int, message: String, status: Int) =
        send(GuacInstruction("ack", stream.toString(), message, status.toString()))

    private fun send(instruction: GuacInstruction) {
        onSend?.invoke(instruction.encoded())
    }

    private fun set(s: GuacState) {
        if (s == state) return
        state = s
        if (s == GuacState.DISCONNECTED) failTransfers(GuacError("Session fermée", 0x0209))
        onState?.invoke(s)
    }

    private fun fail(error: GuacError) {
        onError?.invoke(error)
        set(GuacState.DISCONNECTED)
    }

    companion object {
        /** Taille des blocs envoyés (comme guacamole-common-js). */
        const val BLOB_SIZE = 6048
        /** Type MIME d'un index de dossier. */
        const val DIRECTORY_MIMETYPE = "application/vnd.glyptodon.guacamole.stream-index+json"

        private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

        private fun unb64(text: String): ByteArray = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
    }
}
