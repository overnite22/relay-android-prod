package io.github.overnite22.relay.sessions

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.github.overnite22.relay.kit.guac.DisplayCommand
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacState
import io.github.overnite22.relay.kit.guac.GuacTransport
import io.github.overnite22.relay.kit.guac.ReceivedFile
import io.github.overnite22.relay.kit.guac.RemoteObject

/** Trame à afficher ; le bitmap appartient à la réserve et doit être rendu via [GuacEngine.release]. */
class Frame(val bitmap: Bitmap?, val width: Int, val height: Int)

class CursorImage(val bitmap: Bitmap?, val hotX: Int, val hotY: Int)

/**
 * Moteur d'une session : protocole + rendu, confinés à un fil dédié.
 * Les résultats (trames, états) sont publiés sur le fil principal.
 */
class GuacEngine(private val transport: GuacTransport) {
    var onFrame: ((Frame) -> Unit)? = null
    var onCursor: ((CursorImage) -> Unit)? = null
    var onState: ((GuacState, String?) -> Unit)? = null
    var onClipboard: ((String) -> Unit)? = null
    var onName: ((String) -> Unit)? = null
    var onFilesystem: ((Int, String) -> Unit)? = null
    var onFileReceived: ((ReceivedFile) -> Unit)? = null
    var onFileProgress: ((String, Int) -> Unit)? = null

    private val thread = HandlerThread("guac-engine", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val queue = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val core = GuacClientCore()
    private val display = GuacDisplay()
    private var lastError: String? = null
    private var stopped = false

    private val ping = object : Runnable {
        override fun run() {
            if (core.state == GuacState.DISCONNECTED) return
            core.ping()
            queue.postDelayed(this, 5_000)
        }
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    fun start() = queue.post {
        core.onSend = { transport.send(it) }
        core.onDisplay = { handle(it) }
        core.onState = { publishState(it) }
        core.onError = { lastError = it.description }
        core.onClipboard = { text -> ui { onClipboard?.invoke(text) } }
        core.onFilesystem = { index, name -> ui { onFilesystem?.invoke(index, name) } }
        core.onFileReceived = { file -> ui { onFileReceived?.invoke(file) } }
        core.onFileProgress = { name, bytes -> ui { onFileProgress?.invoke(name, bytes) } }
        core.onName = { name -> ui { onName?.invoke(name) } }
        transport.onText = { text -> queue.post { core.receive(text) } }
        transport.onClose = { reason ->
            queue.post {
                if (lastError == null && reason != null && core.state != GuacState.DISCONNECTED && core.state != GuacState.DISCONNECTING) {
                    lastError = reason
                }
                core.tunnelClosed()
            }
        }
        core.connecting()
        transport.start()
        queue.postDelayed(ping, 5_000)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        queue.post {
            queue.removeCallbacks(ping)
            core.disconnect()
            transport.close()
            thread.quitSafely()
        }
    }

    // region Entrées

    fun mouse(x: Int, y: Int, buttons: Int) = queue.post { core.sendMouse(x, y, buttons) }
    fun key(keysym: Int, pressed: Boolean) = queue.post { core.sendKey(keysym, pressed) }
    fun tap(keysym: Int) = queue.post { core.tapKey(keysym) }
    fun type(text: String) = queue.post { core.type(text) }
    fun clipboard(text: String) = queue.post { core.sendClipboard(text) }
    fun resize(width: Int, height: Int) = queue.post { core.sendSize(width, height) }

    /** Combinaison : modificateurs enfoncés, touche, puis relâchement inverse. */
    fun chord(modifiers: List<Int>, keysym: Int) = queue.post {
        for (m in modifiers) core.sendKey(m, true)
        core.tapKey(keysym)
        for (m in modifiers.asReversed()) core.sendKey(m, false)
    }

    // endregion

    // region Fichiers

    fun requestObject(obj: Int, name: String, progress: ((Int) -> Unit)? = null, completion: (Result<RemoteObject>) -> Unit) = queue.post {
        core.requestObject(obj, name, { bytes -> ui { progress?.invoke(bytes) } }) { result -> ui { completion(result) } }
    }

    fun putObject(
        obj: Int, name: String, mimetype: String, data: ByteArray, progress: ((Int) -> Unit)? = null,
        completion: (Result<Unit>) -> Unit,
    ) = queue.post {
        core.putObject(obj, name, mimetype, data, { bytes -> ui { progress?.invoke(bytes) } }) { result -> ui { completion(result) } }
    }

    // endregion

    /** Rend une trame affichée à la réserve (appelé par l'écran quand elle est remplacée). */
    fun release(frame: Frame) {
        frame.bitmap?.let { display.release(it) }
    }

    private fun handle(command: DisplayCommand) {
        if (command is DisplayCommand.Sync) {
            val frame = Frame(display.composite(), display.width, display.height)
            ui { onFrame?.invoke(frame) }
            core.acknowledgeSync(command.timestamp)
            return
        }
        if (display.apply(command)) {
            val cursor = CursorImage(display.cursorImage, display.cursorHotX, display.cursorHotY)
            ui { onCursor?.invoke(cursor) }
        }
    }

    private fun publishState(state: GuacState) {
        val error = if (state == GuacState.DISCONNECTED) lastError else null
        if (state == GuacState.DISCONNECTED) queue.removeCallbacks(ping)
        ui { onState?.invoke(state, error) }
    }
}
