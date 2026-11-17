package io.github.overnite22.relay.sessions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.overnite22.relay.kit.guac.Keysym
import kotlin.math.abs
import kotlin.math.hypot

/** Ce que l'écran distant pilote : une session, ou le lecteur d'enregistrements (sans entrées). */
interface ScreenHost {
    val screen: ScreenModel
    fun attach(v: RemoteScreenView) {}
    fun detach(v: RemoteScreenView) {}
    fun viewResized(width: Int, height: Int, density: Float) {}
    fun insertText(text: String) {}
    fun sendSpecial(keysym: Int) {}
    fun key(keysym: Int, pressed: Boolean) {}
}

/** Écran distant : dessine les trames et transmet gestes, clavier logiciel et clavier physique au contrôleur. */
@SuppressLint("ViewConstructor")
class RemoteScreenView(context: Context, private val controller: ScreenHost) : View(context) {
    private val model get() = controller.screen
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private val cursorPaint = Paint().apply { isFilterBitmap = false }
    private val fallbackFill = Paint().apply { color = Color.argb(217, 255, 255, 255); isAntiAlias = true }
    private val fallbackStroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; isAntiAlias = true }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val pressed = HashMap<Int, Int>()

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Écran distant"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        model.onInvalidate = { postInvalidateOnAnimation() }
        model.onHaptic = { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        controller.attach(this)
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        controller.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        model.viewSize(w.toFloat(), h.toFloat())
        controller.viewResized(w, h, density)
    }

    // region Dessin

    override fun onDraw(canvas: Canvas) {
        val frame = model.frame ?: return
        val bitmap = frame.bitmap ?: return
        if (bitmap.isRecycled) return
        val r = model.screenRect
        // Agrandi : lissage ; à l'échelle 1 ou en réduction marquée : pixels nets.
        bitmapPaint.isFilterBitmap = model.scale !in 0.99f..1.01f
        canvas.drawBitmap(bitmap, Rect(0, 0, frame.width, frame.height), r, bitmapPaint)
        if (!model.showsPointer || model.remoteWidth <= 0) return
        val s = model.scale
        val c = model.cursor
        canvas.save()
        if (c?.bitmap != null) {
            canvas.translate(r.left + (model.pointerX - c.hotX) * s, r.top + (model.pointerY - c.hotY) * s)
            canvas.scale(s, s)
            canvas.drawBitmap(c.bitmap, 0f, 0f, cursorPaint)
        } else {
            val x = r.left + model.pointerX * s
            val y = r.top + model.pointerY * s
            val radius = 6 * density
            canvas.drawCircle(x, y, radius, fallbackFill)
            fallbackStroke.strokeWidth = 1.5f * density
            canvas.drawCircle(x, y, radius, fallbackStroke)
        }
        canvas.restore()
    }

    // endregion

    // region Gestes

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false
    private var held = false
    private var multi = false
    private var multiStart = 0L
    private var multiMoved = false
    private var midX = 0f
    private var midY = 0f
    private var pinchTotal = 1f

    private val longPress = Runnable {
        if (!moved && !multi) {
            held = true
            model.longPress(lastX, lastY)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchTotal = 1f
                model.pinchBegin(d.focusX, d.focusY)
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchTotal *= d.scaleFactor
                if (abs(pinchTotal - 1f) > 0.04f) {
                    multiMoved = true
                    model.pinch(d.scaleFactor, d.focusX, d.focusY)
                }
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = SystemClock.uptimeMillis()
                moved = false; dragging = false; held = false; multi = false
                postDelayed(longPress, 400)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (dragging) model.drag(lastX, lastY, 0f, 0f, 2, held)
                dragging = false
                if (!multi) {
                    multi = true
                    multiStart = SystemClock.uptimeMillis()
                    multiMoved = false
                }
                midX = mid(e, true); midY = mid(e, false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val mx = mid(e, true)
                        val my = mid(e, false)
                        val dx = mx - midX
                        val dy = my - midY
                        // Pendant un vrai pincement, pas de molette.
                        if (!scaleDetector.isInProgress || abs(pinchTotal - 1f) <= 0.04f) {
                            if (abs(dx) + abs(dy) > 1f) multiMoved = true
                            model.twoFingerPan(dx, dy, 24 * density, false)
                        }
                        midX = mx; midY = my
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        if (!held) removeCallbacks(longPress)
                        dragging = true
                        model.drag(e.x, e.y, e.x - downX, e.y - downY, 0, held)
                    } else if (dragging) {
                        model.drag(e.x, e.y, dx, dy, 1, held)
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multi && e.pointerCount == 2) {
                    if (!multiMoved && SystemClock.uptimeMillis() - multiStart < 350) model.twoFingerTap(midX, midY)
                    model.twoFingerPan(0f, 0f, 24 * density, true)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    multi -> Unit
                    dragging -> model.drag(e.x, e.y, 0f, 0f, 2, held)
                    held -> model.releaseHeld()
                    SystemClock.uptimeMillis() - downTime < 400 -> model.tap(e.x, e.y)
                }
                multi = false; dragging = false; held = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) model.drag(lastX, lastY, 0f, 0f, 2, held) else if (held) model.releaseHeld()
                multi = false; dragging = false; held = false
            }
        }
        return true
    }

    private fun mid(e: MotionEvent, x: Boolean): Float {
        var sum = 0f
        val n = minOf(e.pointerCount, 2)
        for (i in 0 until n) sum += if (x) e.getX(i) else e.getY(i)
        return sum / n
    }

    // endregion

    // region Clavier logiciel

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            /** Remplace le texte en cours de composition par [next] (effacements puis frappe de la différence). */
            private fun recompose(next: String) {
                var p = 0
                while (p < composing.length && p < next.length && composing[p] == next[p]) p++
                val removed = composing.substring(p).codePointCount(0, composing.length - p)
                repeat(removed) { controller.sendSpecial(Keysym.BACKSPACE) }
                if (p < next.length) controller.insertText(next.substring(p))
                composing = next
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                recompose(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                recompose(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) { controller.sendSpecial(Keysym.BACKSPACE) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                controller.sendSpecial(Keysym.ENTER)
                return true
            }
        }
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.restartInput(this)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    // endregion

    // region Clavier physique (et touches envoyées par le clavier logiciel)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val sym = keysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) pressed[keyCode] = sym
        controller.key(sym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val sym = pressed.remove(keyCode) ?: return super.onKeyUp(keyCode, event)
        controller.key(sym, false)
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        controller.insertText(chars)
        return true
    }

    companion object {
        /** Keysym d'une touche Android, ou null si elle ne concerne pas la session (retour, volume…). */
        fun keysym(keyCode: Int, event: KeyEvent): Int? = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> Keysym.ESCAPE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysym.ENTER
            KeyEvent.KEYCODE_TAB -> Keysym.TAB
            KeyEvent.KEYCODE_DEL -> Keysym.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> Keysym.DELETE
            KeyEvent.KEYCODE_DPAD_LEFT -> Keysym.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keysym.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Keysym.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keysym.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Keysym.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keysym.END
            KeyEvent.KEYCODE_PAGE_UP -> Keysym.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keysym.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> Keysym.INSERT
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Keysym.SHIFT_LEFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Keysym.CONTROL_LEFT
            KeyEvent.KEYCODE_ALT_LEFT -> Keysym.ALT_LEFT
            KeyEvent.KEYCODE_ALT_RIGHT -> Keysym.ALT_RIGHT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> Keysym.SUPER_LEFT
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keysym.function(keyCode - KeyEvent.KEYCODE_F1 + 1)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> null
            else -> {
                // Avec Ctrl ou Alt : caractère de base (Ctrl+C envoie « c » avec Ctrl enfoncé).
                val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
                val unicode = event.getUnicodeChar(meta)
                if (unicode <= 0) null else Keysym.fromCodePoint(unicode).takeIf { it != 0 }
            }
        }
    }

    // endregion
}
