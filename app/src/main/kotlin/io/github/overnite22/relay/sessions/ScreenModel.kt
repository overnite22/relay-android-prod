package io.github.overnite22.relay.sessions

import android.graphics.RectF
import io.github.overnite22.relay.kit.guac.MouseButtons
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Mode de pointage sur écran tactile. */
enum class PointerMode(val title: String) {
    /** Le doigt déplace le pointeur comme un pavé tactile ; toucher = clic au pointeur. */
    TOUCHPAD("Pavé tactile"),
    /** Le doigt clique là où il touche. */
    DIRECT("Toucher direct"),
}

/**
 * État de l'écran distant (trame, curseur, zoom, pointeur) et logique des gestes.
 * Vit dans le contrôleur de session : la vue Android ne fait que dessiner et transmettre les gestes.
 */
class ScreenModel {
    var engine: GuacEngine? = null
    /** Rend une trame remplacée à sa réserve (moteur de session ou lecteur). */
    var onRelease: ((Frame) -> Unit)? = null
    var mode = PointerMode.TOUCHPAD
        set(value) {
            field = value
            invalidate()
        }
    /** Faux en relecture : la position du pointeur n'est pas enregistrée. */
    var showsPointer = true
    /** Redessin demandé à la vue attachée. */
    var onInvalidate: (() -> Unit)? = null
    /** Retour haptique (appui long). */
    var onHaptic: (() -> Unit)? = null

    var frame: Frame? = null; private set
    var cursor: CursorImage? = null; private set
    var remoteWidth = 0f; private set
    var remoteHeight = 0f; private set
    var viewWidth = 0f; private set
    var viewHeight = 0f; private set
    var zoom = 1f; private set
    var panX = 0f; private set
    var panY = 0f; private set
    var pointerX = 20f; private set
    var pointerY = 20f; private set
    private var buttons = MouseButtons.NONE
    private var scrollAccumulator = 0f

    // region Affichage

    fun show(next: Frame) {
        val previous = frame
        frame = next
        if (previous != null && previous !== next && previous.bitmap != null && previous.bitmap !== next.bitmap) {
            onRelease?.invoke(previous)
        }
        if (next.width.toFloat() != remoteWidth || next.height.toFloat() != remoteHeight) {
            remoteWidth = next.width.toFloat()
            remoteHeight = next.height.toFloat()
            if (pointerX > remoteWidth || pointerY > remoteHeight) {
                pointerX = remoteWidth / 2
                pointerY = remoteHeight / 2
            }
            clampPan()
        }
        invalidate()
    }

    fun setCursor(c: CursorImage) {
        cursor = c
        invalidate()
    }

    fun viewSize(w: Float, h: Float) {
        viewWidth = w
        viewHeight = h
        clampPan()
        invalidate()
    }

    private val baseScale: Float
        get() = if (remoteWidth <= 0 || remoteHeight <= 0 || viewWidth <= 0) 1f else min(viewWidth / remoteWidth, viewHeight / remoteHeight)

    val scale: Float get() = baseScale * zoom

    /** Position de l'écran distant dans la vue (pixels). */
    val screenRect: RectF
        get() {
            val s = scale
            val w = remoteWidth * s
            val h = remoteHeight * s
            var x = -panX * s
            var y = -panY * s
            if (w <= viewWidth) x = (viewWidth - w) / 2
            if (h <= viewHeight) y = (viewHeight - h) / 2
            return RectF(x, y, x + w, y + h)
        }

    private fun clampPan() {
        val s = scale
        if (s <= 0) return
        panX = panX.coerceIn(0f, max(0f, remoteWidth - viewWidth / s))
        panY = panY.coerceIn(0f, max(0f, remoteHeight - viewHeight / s))
    }

    fun remotePoint(x: Float, y: Float): Pair<Float, Float> {
        val f = screenRect
        val s = max(scale, 0.0001f)
        return ((x - f.left) / s).coerceIn(0f, max(0f, remoteWidth - 1)) to ((y - f.top) / s).coerceIn(0f, max(0f, remoteHeight - 1))
    }

    /** Garde le pointeur visible quand l'écran est zoomé (mode pavé tactile). */
    private fun follow() {
        val s = scale
        if (zoom <= 1.01f || s <= 0) return
        val vw = viewWidth / s
        val vh = viewHeight / s
        val margin = min(vw, vh) * 0.12f
        if (pointerX < panX + margin) panX = pointerX - margin
        if (pointerX > panX + vw - margin) panX = pointerX - vw + margin
        if (pointerY < panY + margin) panY = pointerY - margin
        if (pointerY > panY + vh - margin) panY = pointerY - vh + margin
        clampPan()
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun invalidate() {
        onInvalidate?.invoke()
    }

    // endregion

    // region Souris

    private fun sendMouse() {
        engine?.mouse(pointerX.roundToInt(), pointerY.roundToInt(), buttons)
        invalidate()
    }

    private fun click(button: Int) {
        buttons = buttons or button
        sendMouse()
        buttons = buttons and button.inv()
        sendMouse()
    }

    private fun pointTo(x: Float, y: Float) {
        val (rx, ry) = remotePoint(x, y)
        pointerX = rx
        pointerY = ry
    }

    fun tap(x: Float, y: Float) {
        if (mode == PointerMode.DIRECT) pointTo(x, y)
        click(MouseButtons.LEFT)
    }

    fun twoFingerTap(x: Float, y: Float) {
        if (mode == PointerMode.DIRECT) pointTo(x, y)
        click(MouseButtons.RIGHT)
    }

    /** Appui long : clic droit (direct) ou début de glisser-déposer (pavé tactile). */
    fun longPress(x: Float, y: Float) {
        onHaptic?.invoke()
        if (mode == PointerMode.DIRECT) {
            pointTo(x, y)
            click(MouseButtons.RIGHT)
        } else {
            buttons = buttons or MouseButtons.LEFT
            sendMouse()
        }
    }

    /** Glisser à un doigt. [phase] : 0 début, 1 déplacement, 2 fin. [held] : après un appui long (pavé tactile). */
    fun drag(x: Float, y: Float, dx: Float, dy: Float, phase: Int, held: Boolean) {
        when (mode) {
            PointerMode.TOUCHPAD -> {
                if (phase != 2) movePointer(dx, dy)
                if (phase == 2 && held) {
                    buttons = buttons and MouseButtons.LEFT.inv()
                    sendMouse()
                }
            }
            PointerMode.DIRECT -> {
                if (held) return
                pointTo(x, y)
                when (phase) {
                    0 -> buttons = buttons or MouseButtons.LEFT
                    2 -> buttons = buttons and MouseButtons.LEFT.inv()
                }
                sendMouse()
            }
        }
    }

    /** Fin d'un appui long sans déplacement (pavé tactile) : relâche le bouton. */
    fun releaseHeld() {
        if (buttons and MouseButtons.LEFT != 0) {
            buttons = buttons and MouseButtons.LEFT.inv()
            sendMouse()
        }
    }

    private fun movePointer(dx: Float, dy: Float) {
        val s = max(scale, 0.0001f)
        val speed = 1.6f
        pointerX = (pointerX + dx / s * speed / max(zoom, 1f)).coerceIn(0f, max(0f, remoteWidth - 1))
        pointerY = (pointerY + dy / s * speed / max(zoom, 1f)).coerceIn(0f, max(0f, remoteHeight - 1))
        sendMouse()
        follow()
    }

    /** Glisser à deux doigts : molette, ou déplacement de la vue zoomée en mode direct. [step] : pixels par cran. */
    fun twoFingerPan(dx: Float, dy: Float, step: Float, ended: Boolean) {
        if (mode == PointerMode.DIRECT && zoom > 1.01f) {
            panX -= dx / scale
            panY -= dy / scale
            clampPan()
            invalidate()
            return
        }
        scrollAccumulator += dy
        while (abs(scrollAccumulator) >= step) {
            click(if (scrollAccumulator > 0) MouseButtons.SCROLL_UP else MouseButtons.SCROLL_DOWN)
            scrollAccumulator += if (scrollAccumulator > 0) -step else step
        }
        if (ended) scrollAccumulator = 0f
    }

    private var pinchFocus = 0f to 0f

    fun pinchBegin(focusX: Float, focusY: Float) {
        pinchFocus = remotePoint(focusX, focusY)
    }

    /** Pincer : le point du bureau sous les doigts reste sous les doigts. */
    fun pinch(factor: Float, focusX: Float, focusY: Float) {
        zoom = (zoom * factor).coerceIn(1f, 6f)
        val s = scale
        panX = pinchFocus.first - focusX / s
        panY = pinchFocus.second - focusY / s
        clampPan()
        invalidate()
    }

    // endregion
}
