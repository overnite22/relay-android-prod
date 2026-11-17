package io.github.overnite22.relay.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.core.Prefs
import io.github.overnite22.relay.kit.Entry
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.QuickConnectRequest
import io.github.overnite22.relay.kit.SessionOptions
import io.github.overnite22.relay.kit.SessionTicket
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacState
import io.github.overnite22.relay.kit.guac.Keysym
import io.github.overnite22.relay.kit.guac.RemoteItem
import io.github.overnite22.relay.kit.guac.RemotePath
import java.io.File
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Ce qu'on ouvre : une entrée de l'inventaire ou une connexion rapide. */
sealed class SessionTarget {
    data class ForEntry(val entry: Entry, val record: Boolean) : SessionTarget()
    data class Quick(val request: QuickConnectRequest, val label: String) : SessionTarget()

    val id: String
        get() = when (this) {
            is ForEntry -> "entry-${entry.id}-$record"
            is Quick -> "quick-${request.kind.raw}-${request.host}-${request.port}"
        }

    val title: String get() = when (this) {
        is ForEntry -> entry.name
        is Quick -> label
    }

    val kind: EntryKind get() = when (this) {
        is ForEntry -> entry.kind
        is Quick -> request.kind
    }

    val recording: Boolean get() = when (this) {
        is ForEntry -> record
        is Quick -> request.record
    }
}

/** Réglages d'affichage des sessions (Réglages de l'app). */
object SessionPreferences {
    /** [width]/[height] en pixels de la vue, [density] pixels par dp. */
    fun options(kind: EntryKind, width: Int, height: Int, density: Float, record: Boolean, prefs: Prefs): SessionOptions {
        val tz = TimeZone.getDefault().id
        if (kind == EntryKind.SSH) {
            return SessionOptions(width, height, (96 * maxOf(density, 1f) * prefs.sshText).roundToInt(), tz, record)
        }
        val r = prefs.rdpResolution
        return SessionOptions((width / density * r).roundToInt(), (height / density * r).roundToInt(), 96, tz, record)
    }
}

sealed class SessionPhase {
    data object Preparing : SessionPhase()
    data object Connecting : SessionPhase()
    data object Connected : SessionPhase()
    data class Ended(val error: String?) : SessionPhase()
}

class FileError(message: String) : Exception(message)

/** Une session ouverte (SSH, RDP, VNC, console Proxmox) : connexion, écran, clavier, fichiers. */
class SessionController(val target: SessionTarget, private val app: AppSession) : ScreenHost {
    val uid: String = UUID.randomUUID().toString()
    var phase by mutableStateOf<SessionPhase>(SessionPhase.Preparing); private set
    var remoteName by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null); private set
    /** Système de fichiers de la session (SFTP en SSH, lecteur « Relay » en RDP). */
    var filesystem by mutableStateOf<Pair<Int, String>?>(null); private set
    /** Fichier reçu du serveur, à proposer au partage (RDP : dossier Download). */
    var sharedFile by mutableStateOf<File?>(null)
    var incoming by mutableStateOf<Pair<String, Int>?>(null); private set
    /** Modificateurs collants de la barre de touches. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var mode by mutableStateOf(if (target.kind == EntryKind.SSH) PointerMode.DIRECT else PointerMode.TOUCHPAD)
        private set

    override val screen = ScreenModel().apply { mode = this@SessionController.mode }
    private var engine: GuacEngine? = null
    private var view: RemoteScreenView? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDensity = 2f
    private val main = Handler(Looper.getMainLooper())
    private val resize = Runnable { sendResize() }
    private val clearToast = Runnable { toast = null }

    val title: String get() = remoteName ?: target.title
    val isEnded: Boolean get() = phase is SessionPhase.Ended

    fun updateMode(m: PointerMode) {
        mode = m
        screen.mode = m
    }

    // region Vue

    override fun attach(v: RemoteScreenView) {
        view = v
    }

    override fun detach(v: RemoteScreenView) {
        if (view === v) view = null
    }

    /** Taille de la vue : la première taille valide démarre la session, les suivantes adaptent la résolution. */
    override fun viewResized(width: Int, height: Int, density: Float) {
        if (width < 10 || height < 10) return
        if (phase == SessionPhase.Preparing) {
            lastWidth = width; lastHeight = height; lastDensity = density
            start()
            return
        }
        if (width == lastWidth && height == lastHeight) return
        // RDP : l'apparition du clavier ne change que la hauteur ; on garde alors la résolution du bureau distant.
        val widthChanged = width != lastWidth
        lastWidth = width; lastHeight = height; lastDensity = density
        if (target.kind == EntryKind.RDP && !widthChanged) return
        if (phase != SessionPhase.Connected || target.kind == EntryKind.VNC || target.kind == EntryKind.PVE) return
        main.removeCallbacks(resize)
        main.postDelayed(resize, 600)
    }

    private fun sendResize() {
        val o = SessionPreferences.options(target.kind, lastWidth, lastHeight, lastDensity, false, app.prefs)
        engine?.resize(o.width, o.height)
    }

    // endregion

    // region Connexion

    private fun start() {
        if (phase != SessionPhase.Preparing && !isEnded) return
        phase = SessionPhase.Connecting
        app.scope.launch { open() }
    }

    private suspend fun open() {
        val client = app.client ?: run {
            phase = SessionPhase.Ended("Appareil non appairé.")
            return
        }
        val options = SessionPreferences.options(target.kind, lastWidth, lastHeight, lastDensity, target.recording, app.prefs)
        val ticket: SessionTicket = try {
            when (val t = target) {
                is SessionTarget.ForEntry -> client.connect(t.entry.id, options.copy(native = t.entry.kind == EntryKind.PVE))
                    .also { app.noteOpened(t.entry.id) }
                is SessionTarget.Quick -> client.quickConnect(
                    t.request.copy(width = options.width, height = options.height, dpi = options.dpi, timezone = options.timezone),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phase = SessionPhase.Ended(e.message)
            return
        }
        val transport = app.makeTransport(ticket) ?: run {
            phase = SessionPhase.Ended("Adresse du tunnel invalide.")
            return
        }
        val e = GuacEngine(transport)
        e.onFrame = { screen.show(it) }
        e.onCursor = { screen.setCursor(it) }
        e.onName = { remoteName = it }
        e.onClipboard = { text ->
            app.context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Relay", text))
            flash("Presse-papiers distant copié")
        }
        e.onState = { state, error -> update(state, error) }
        e.onFilesystem = { index, name -> filesystem = index to name }
        e.onFileProgress = { name, bytes -> incoming = name to bytes }
        e.onFileReceived = { file ->
            incoming = null
            try {
                sharedFile = LocalFiles.save(app.context, file.data.data, file.name)
            } catch (ex: Exception) {
                flash("Fichier reçu mais non enregistré : ${ex.message}")
            }
        }
        screen.engine = e
        screen.onRelease = { e.release(it) }
        engine = e
        e.start()
    }

    private fun update(state: GuacState, error: String?) {
        when (state) {
            GuacState.CONNECTED -> phase = SessionPhase.Connected
            GuacState.DISCONNECTED -> {
                if (!isEnded) phase = SessionPhase.Ended(error)
                view?.hideKeyboard()
            }
            else -> Unit
        }
    }

    fun reconnect() {
        engine?.stop()
        engine = null
        filesystem = null
        phase = SessionPhase.Preparing
        if (lastWidth > 10) start()
    }

    fun close() {
        main.removeCallbacks(resize)
        engine?.stop()
        engine = null
        view?.hideKeyboard()
    }

    // endregion

    // region Clavier

    fun toggleKeyboard(visible: Boolean) {
        if (visible) view?.hideKeyboard() else view?.showKeyboard()
    }

    private fun consumeModifiers(): List<Int> {
        val out = ArrayList<Int>()
        if (ctrl) out.add(Keysym.CONTROL_LEFT)
        if (alt) out.add(Keysym.ALT_LEFT)
        ctrl = false
        alt = false
        return out
    }

    /** Texte tapé au clavier logiciel (avec les modificateurs collants éventuels). */
    override fun insertText(text: String) {
        val modifiers = consumeModifiers()
        if (modifiers.isNotEmpty() && text.codePointCount(0, text.length) == 1) {
            engine?.chord(modifiers, Keysym.fromCodePoint(text.lowercase().codePointAt(0)))
        } else {
            engine?.type(text)
        }
    }

    override fun sendSpecial(keysym: Int) {
        engine?.chord(consumeModifiers(), keysym)
    }

    /** Touche du clavier physique (appui ou relâchement). */
    override fun key(keysym: Int, pressed: Boolean) {
        engine?.key(keysym, pressed)
    }

    fun sendCtrlAltDel() {
        engine?.chord(listOf(Keysym.CONTROL_LEFT, Keysym.ALT_LEFT), Keysym.DELETE)
    }

    fun sendKey(modifiers: List<Int>, keysym: Int) {
        engine?.chord(modifiers, keysym)
    }

    private fun clipboardText(): String? =
        app.context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(app.context)?.toString()?.takeIf { it.isNotEmpty() }

    /** Copie le presse-papiers du téléphone vers la session distante. */
    fun sendClipboard() {
        val text = clipboardText() ?: return flash("Presse-papiers vide")
        engine?.clipboard(text)
        flash("Presse-papiers envoyé à la session")
    }

    /** Tape le presse-papiers comme au clavier (fonctionne partout, y compris aux invites de mot de passe). */
    fun typeClipboard() {
        val text = clipboardText() ?: return flash("Presse-papiers vide")
        engine?.type(text)
        flash("Texte saisi")
    }

    fun resetZoom() = screen.resetZoom()

    fun flash(message: String) {
        toast = message
        main.removeCallbacks(clearToast)
        main.postDelayed(clearToast, 2_000)
    }

    // endregion

    // region Fichiers

    suspend fun list(path: String): List<RemoteItem> {
        val value = fetch(path, null)
        if (value.mimetype != GuacClientCore.DIRECTORY_MIMETYPE) throw FileError("Ce n'est pas un dossier.")
        return RemoteItem.parseIndex(value.data.data)
    }

    suspend fun download(path: String, progress: (Int) -> Unit): File {
        val value = fetch(path, progress)
        return LocalFiles.save(app.context, value.data.data, RemotePath.basename(path))
    }

    private suspend fun fetch(path: String, progress: ((Int) -> Unit)?): io.github.overnite22.relay.kit.guac.RemoteObject {
        val e = engine ?: throw FileError("Fichiers indisponibles dans cette session.")
        val fs = filesystem ?: throw FileError("Fichiers indisponibles dans cette session.")
        val result = suspendCancellableCoroutine { cont -> e.requestObject(fs.first, path, progress) { cont.resume(it) } }
        return result.getOrElse { throw FileError((it as? io.github.overnite22.relay.kit.guac.GuacError)?.description ?: it.message ?: "Erreur") }
    }

    suspend fun upload(data: ByteArray, path: String, mimetype: String, progress: (Int) -> Unit) {
        val e = engine ?: throw FileError("Fichiers indisponibles dans cette session.")
        val fs = filesystem ?: throw FileError("Fichiers indisponibles dans cette session.")
        val result = suspendCancellableCoroutine { cont -> e.putObject(fs.first, path, mimetype, data, progress) { cont.resume(it) } }
        result.exceptionOrNull()?.let { throw FileError((it as? io.github.overnite22.relay.kit.guac.GuacError)?.description ?: it.message ?: "Erreur") }
    }

    // endregion
}
