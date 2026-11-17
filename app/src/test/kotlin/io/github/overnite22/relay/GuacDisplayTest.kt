package io.github.overnite22.relay

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import io.github.overnite22.relay.core.Prefs
import io.github.overnite22.relay.kit.EntryKind
import io.github.overnite22.relay.kit.guac.Bytes
import io.github.overnite22.relay.kit.guac.DisplayCommand
import io.github.overnite22.relay.kit.guac.ScriptedTransport
import io.github.overnite22.relay.sessions.Frame
import io.github.overnite22.relay.sessions.GuacDisplay
import io.github.overnite22.relay.sessions.GuacEngine
import io.github.overnite22.relay.sessions.SessionPreferences
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Vérifie le rendu Canvas pixel par pixel (orientation, copies, images, calques) avec le moteur graphique natif. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuacDisplayTest {
    private fun px(b: Bitmap?, x: Int, y: Int): IntArray {
        val c = b!!.getPixel(x, y)
        return intArrayOf(Color.red(c), Color.green(c), Color.blue(c), Color.alpha(c))
    }

    private fun assertPixel(expected: IntArray, b: Bitmap?, x: Int, y: Int, message: String = "") {
        val p = px(b, x, y)
        assertTrue("$message attendu ${expected.toList()} obtenu ${p.toList()} en ($x,$y)", p.zip(expected.toList()).all { (a, e) -> kotlin.math.abs(a - e) <= 3 })
    }

    private val transparent = intArrayOf(0, 0, 0, 0)

    @Test fun fillIsTopLeftOriented() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 100, 50))
        d.apply(DisplayCommand.Rect(0, 0, 0, 10, 5))
        d.apply(DisplayCommand.Cfill(0, 14, 255, 0, 0, 255))
        val img = d.composite()!!
        assertEquals(100, img.width)
        assertEquals(50, img.height)
        assertPixel(intArrayOf(255, 0, 0, 255), img, 2, 2)
        assertPixel(transparent, img, 2, 45, "le bas doit rester vide")
    }

    @Test fun copyAndResizeKeepContent() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 40, 40))
        d.apply(DisplayCommand.Rect(0, 0, 0, 4, 4))
        d.apply(DisplayCommand.Cfill(0, 14, 0, 255, 0, 255))
        d.apply(DisplayCommand.Copy(0, 0, 0, 4, 4, 14, 0, 30, 20))
        d.apply(DisplayCommand.Size(0, 60, 60))
        val img = d.composite()
        assertPixel(intArrayOf(0, 255, 0, 255), img, 31, 21)
        assertPixel(intArrayOf(0, 255, 0, 255), img, 1, 1)
        assertPixel(transparent, img, 31, 30)
    }

    @Test fun overlappingCopyScrollsContent() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 10, 10))
        d.apply(DisplayCommand.Rect(0, 0, 5, 10, 5))
        d.apply(DisplayCommand.Cfill(0, 12, 0, 0, 255, 255))
        // Défilement d'une ligne vers le haut (source et destination se chevauchent).
        d.apply(DisplayCommand.Copy(0, 0, 1, 10, 9, 12, 0, 0, 0))
        val img = d.composite()
        assertPixel(intArrayOf(0, 0, 255, 255), img, 5, 4)
        assertPixel(transparent, img, 5, 3)
    }

    @Test fun pngImageAndBufferCursor() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 200, 100))
        d.apply(DisplayCommand.Image(0, 14, 10, 20, "image/png", Bytes(Base64.getDecoder().decode(GuacFixture.PNG))))
        val img = d.composite()
        // Damier de 8 px : premier carré bleu Relay, le suivant blanc.
        assertPixel(intArrayOf(43, 125, 233, 255), img, 12, 22)
        assertPixel(intArrayOf(255, 255, 255, 255), img, 20, 22)
        d.apply(DisplayCommand.Size(-1, 8, 8))
        d.apply(DisplayCommand.Rect(-1, 0, 0, 8, 8))
        d.apply(DisplayCommand.Cfill(-1, 14, 255, 255, 0, 255))
        assertTrue(d.apply(DisplayCommand.Cursor(1, 1, -1, 0, 0, 8, 8)))
        assertEquals(8, d.cursorImage?.width)
        // Les tampons (< 0) ne sont jamais composés à l'écran.
        assertPixel(transparent, d.composite(), 2, 2)
    }

    @Test fun visibleLayerIsComposited() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 50, 50))
        d.apply(DisplayCommand.Size(2, 10, 10))
        d.apply(DisplayCommand.Rect(2, 0, 0, 10, 10))
        d.apply(DisplayCommand.Cfill(2, 14, 0, 0, 255, 255))
        d.apply(DisplayCommand.Move(2, 0, 30, 5, 1))
        val img = d.composite()
        assertPixel(intArrayOf(0, 0, 255, 255), img, 32, 7)
        assertPixel(transparent, img, 5, 7)
        d.apply(DisplayCommand.Dispose(2))
        assertPixel(transparent, d.composite(), 32, 7)
    }

    @Test fun clipAndPushPop() {
        val d = GuacDisplay()
        d.apply(DisplayCommand.Size(0, 20, 20))
        d.apply(DisplayCommand.Push(0))
        d.apply(DisplayCommand.Rect(0, 0, 0, 5, 20))
        d.apply(DisplayCommand.Clip(0))
        d.apply(DisplayCommand.Rect(0, 0, 0, 20, 20))
        d.apply(DisplayCommand.Cfill(0, 14, 255, 255, 255, 255))
        d.apply(DisplayCommand.Pop(0))
        var img = d.composite()
        assertPixel(intArrayOf(255, 255, 255, 255), img, 2, 10)
        assertPixel(transparent, img, 10, 10)
        d.apply(DisplayCommand.Rect(0, 10, 10, 2, 2))
        d.apply(DisplayCommand.Cfill(0, 14, 255, 0, 0, 255))
        img = d.composite()
        assertPixel(intArrayOf(255, 0, 0, 255), img, 10, 10, "le clip doit être levé après pop")
    }

    @Test fun fixtureScriptRendersThroughEngine() {
        val engine = GuacEngine(ScriptedTransport(GuacFixture.script))
        var frame: Frame? = null
        engine.onFrame = { frame = it }
        engine.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (frame == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertNotNull("aucune trame", frame)
        assertEquals(1170, frame!!.width)
        assertEquals(1800, frame!!.height)
        assertPixel(intArrayOf(43, 125, 233, 255), frame!!.bitmap, 5, 5)
        assertPixel(intArrayOf(43, 125, 233, 255), frame!!.bitmap, 202, 1252, "copie de l'image")
        engine.stop()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionPreferencesTest {
    private val prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test fun sshUsesNativePixelsAndScaledDpi() {
        prefs.sshText = 0.85f
        val o = SessionPreferences.options(EntryKind.SSH, 1170, 2100, 3f, true, prefs)
        assertEquals(1170, o.width)
        assertEquals(2100, o.height)
        assertEquals(245, o.dpi)
        assertTrue(o.record)
    }

    @Test fun rdpUsesResolutionSetting() {
        prefs.rdpResolution = 2f
        val o = SessionPreferences.options(EntryKind.RDP, 2532, 1170, 3f, false, prefs)
        assertEquals(1688, o.width)
        assertEquals(780, o.height)
        assertEquals(96, o.dpi)
    }
}
