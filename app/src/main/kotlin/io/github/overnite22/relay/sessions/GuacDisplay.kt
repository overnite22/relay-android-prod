package io.github.overnite22.relay.sessions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import io.github.overnite22.relay.kit.guac.ChannelMask
import io.github.overnite22.relay.kit.guac.DisplayCommand
import kotlin.math.PI

/** Calque Guacamole : bitmap ARGB et canevas associé (coordonnées haut-gauche, comme Guacamole). */
class GuacLayer(val id: Int) {
    var width = 0; private set
    var height = 0; private set
    var bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); private set
    var canvas = Canvas(bitmap); private set
    var path = Path()
    private var baseSave = canvas.save()
    var depth = 0
    var parent = 0
    var x = 0
    var y = 0
    var z = 0
    var opacity = 255

    val isEmpty get() = width <= 0 || height <= 0

    /** Redimensionne en conservant le contenu (ancré en haut à gauche). */
    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        val old = if (isEmpty) null else bitmap
        val next = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(next)
        old?.let { c.drawBitmap(it, 0f, 0f, null) }
        bitmap = next
        canvas = c
        baseSave = canvas.save()
        width = w
        height = h
        depth = 0
        path = Path()
    }

    fun consumePath(): Path {
        val p = path
        path = Path()
        return p
    }

    fun push() {
        canvas.save()
        depth++
    }

    fun pop() {
        if (depth == 0) return
        canvas.restore()
        depth--
    }

    /** Supprime l'empilement, le clip et les transformations. */
    fun reset() {
        canvas.restoreToCount(baseSave)
        baseSave = canvas.save()
        depth = 0
        path = Path()
    }

    /** Ramène la transformation à l'identité (le clip est conservé). */
    fun identity() {
        canvas.setMatrix(Matrix())
    }
}

/** Réserve de bitmaps d'affichage : une trame n'est réutilisée qu'après avoir été remplacée à l'écran. */
class FramePool {
    private val free = ArrayList<Bitmap>()

    @Synchronized fun obtain(w: Int, h: Int): Bitmap {
        val i = free.indexOfFirst { it.width == w && it.height == h }
        if (i >= 0) return free.removeAt(i)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    @Synchronized fun release(b: Bitmap) {
        if (free.size < 3) free.add(b)
    }
}

/** Affichage Guacamole complet : calques, tampons, curseur et composition. */
class GuacDisplay(private val pool: FramePool = FramePool()) {
    val layers = HashMap<Int, GuacLayer>().apply { put(0, GuacLayer(0)) }
    var cursorImage: Bitmap? = null; private set
    var cursorHotX = 0; private set
    var cursorHotY = 0; private set

    val width get() = layer(0).width
    val height get() = layer(0).height

    private val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = false }

    fun layer(id: Int): GuacLayer = layers.getOrPut(id) { GuacLayer(id) }

    private fun paintFor(mask: Int, style: Paint.Style = Paint.Style.FILL): Paint = paint.apply {
        reset()
        isAntiAlias = true
        isFilterBitmap = false
        this.style = style
        xfermode = mode(mask)?.let { PorterDuffXfermode(it) }
    }

    /** Applique une commande ; renvoie vrai si le curseur a changé. */
    fun apply(command: DisplayCommand): Boolean {
        when (command) {
            is DisplayCommand.Size -> layer(command.layer).resize(maxOf(0, command.width), maxOf(0, command.height))
            is DisplayCommand.Image -> {
                val image = BitmapFactory.decodeByteArray(command.data.data, 0, command.data.size) ?: return false
                layer(command.layer).canvas.drawBitmap(image, command.x.toFloat(), command.y.toFloat(), paintFor(command.mask))
                image.recycle()
            }
            is DisplayCommand.Copy -> copy(command.src, command.sx, command.sy, command.width, command.height, command.dst, command.dx, command.dy, command.mask)
            is DisplayCommand.Transfer -> copy(command.src, command.sx, command.sy, command.width, command.height, command.dst, command.dx, command.dy, ChannelMask.SRC.raw)
            is DisplayCommand.Rect -> layer(command.layer).path.addRect(
                command.x.toFloat(), command.y.toFloat(), (command.x + command.width).toFloat(), (command.y + command.height).toFloat(),
                Path.Direction.CW,
            )
            is DisplayCommand.Start -> layer(command.layer).path.moveTo(command.x.toFloat(), command.y.toFloat())
            is DisplayCommand.Line -> layer(command.layer).path.let {
                if (it.isEmpty) it.moveTo(command.x.toFloat(), command.y.toFloat()) else it.lineTo(command.x.toFloat(), command.y.toFloat())
            }
            is DisplayCommand.Curve -> layer(command.layer).path.let {
                if (it.isEmpty) it.moveTo(command.cp1x.toFloat(), command.cp1y.toFloat())
                it.cubicTo(
                    command.cp1x.toFloat(), command.cp1y.toFloat(), command.cp2x.toFloat(), command.cp2y.toFloat(),
                    command.x.toFloat(), command.y.toFloat(),
                )
            }
            is DisplayCommand.Arc -> {
                val r = command.radius.toFloat()
                val oval = RectF(command.x - r, command.y - r, command.x + r, command.y + r)
                var sweep = command.end - command.start
                if (command.negative && sweep > 0) sweep -= 2 * PI
                if (!command.negative && sweep < 0) sweep += 2 * PI
                layer(command.layer).path.arcTo(oval, Math.toDegrees(command.start).toFloat(), Math.toDegrees(sweep).toFloat(), false)
            }
            is DisplayCommand.Close -> layer(command.layer).path.let { if (!it.isEmpty) it.close() }
            is DisplayCommand.Clip -> {
                val l = layer(command.layer)
                val p = l.consumePath()
                if (!p.isEmpty) l.canvas.clipPath(p)
            }
            is DisplayCommand.Push -> layer(command.layer).push()
            is DisplayCommand.Pop -> layer(command.layer).pop()
            is DisplayCommand.Reset -> layer(command.layer).reset()
            is DisplayCommand.Identity -> layer(command.layer).identity()
            is DisplayCommand.Transform -> layer(command.layer).canvas.concat(matrix(command.a, command.b, command.c, command.d, command.e, command.f))
            is DisplayCommand.Distort -> {
                val l = layer(command.layer)
                l.identity()
                l.canvas.concat(matrix(command.a, command.b, command.c, command.d, command.e, command.f))
            }
            is DisplayCommand.Cfill -> {
                val l = layer(command.layer)
                val p = paintFor(command.mask).apply { color = Color.argb(command.a, command.r, command.g, command.b) }
                l.canvas.drawPath(l.consumePath(), p)
            }
            is DisplayCommand.Cstroke -> stroke(command.layer, command.mask, command.cap, command.join, command.thickness, Color.argb(command.a, command.r, command.g, command.b))
            is DisplayCommand.Lfill -> {
                val l = layer(command.layer)
                val src = layer(command.src)
                val path = l.consumePath()
                if (src.isEmpty) return false
                val pattern = Bitmap.createBitmap(src.bitmap, 0, 0, src.width, src.height)
                val p = paintFor(command.mask).apply { shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
                l.canvas.drawPath(path, p)
                p.shader = null
            }
            // Contour par motif : rare dans guacd ; rendu en gris neutre (comme l'app iPhone).
            is DisplayCommand.Lstroke -> stroke(command.layer, command.mask, command.cap, command.join, command.thickness, Color.rgb(128, 128, 128))
            is DisplayCommand.Dispose -> if (command.layer != 0) layers.remove(command.layer)
            is DisplayCommand.Move -> {
                if (command.layer <= 0) return false
                layer(command.layer).apply {
                    parent = command.parent
                    x = command.x
                    y = command.y
                    z = command.z
                }
            }
            is DisplayCommand.Shade -> layer(command.layer).opacity = command.opacity.coerceIn(0, 255)
            is DisplayCommand.Cursor -> {
                val src = layer(command.src)
                val r = Rect(command.sx, command.sy, command.sx + command.width, command.sy + command.height)
                if (command.width <= 0 || command.height <= 0 || src.isEmpty || !r.intersect(0, 0, src.width, src.height)) {
                    cursorImage = null
                    return true
                }
                cursorImage = Bitmap.createBitmap(src.bitmap, r.left, r.top, r.width(), r.height())
                cursorHotX = command.hotX
                cursorHotY = command.hotY
                return true
            }
            is DisplayCommand.Sync -> Unit
        }
        return false
    }

    private fun stroke(id: Int, mask: Int, cap: Int, join: Int, thickness: Int, color: Int) {
        val l = layer(id)
        val p = paintFor(mask, Paint.Style.STROKE).apply {
            this.color = color
            strokeWidth = thickness.toFloat()
            strokeCap = when (cap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            strokeJoin = when (join) { 0 -> Paint.Join.BEVEL; 2 -> Paint.Join.ROUND; else -> Paint.Join.MITER }
        }
        l.canvas.drawPath(l.consumePath(), p)
    }

    private fun copy(src: Int, sx: Int, sy: Int, w: Int, h: Int, dst: Int, dx: Int, dy: Int, mask: Int) {
        val s = layer(src)
        if (w <= 0 || h <= 0 || s.isEmpty) return
        val r = Rect(sx, sy, sx + w, sy + h)
        if (!r.intersect(0, 0, s.width, s.height)) return
        val left = dx + (r.left - sx).toFloat()
        val top = dy + (r.top - sy).toFloat()
        val d = layer(dst)
        if (src == dst) {
            // Zones qui se chevauchent : on copie d'abord la source.
            val part = Bitmap.createBitmap(s.bitmap, r.left, r.top, r.width(), r.height())
            d.canvas.drawBitmap(part, left, top, paintFor(mask))
            part.recycle()
        } else {
            d.canvas.drawBitmap(s.bitmap, r, RectF(left, top, left + r.width(), top + r.height()), paintFor(mask))
        }
    }

    private fun absoluteOrigin(l: GuacLayer): Pair<Int, Int> {
        var x = 0
        var y = 0
        var cursor: GuacLayer? = l
        var hops = 0
        while (cursor != null && cursor.id != 0 && hops < 32) {
            x += cursor.x
            y += cursor.y
            cursor = layers[cursor.parent]
            hops++
        }
        return x to y
    }

    /** Image de l'écran à présenter (calque 0 + calques visibles), dans un bitmap de la réserve. */
    fun composite(): Bitmap? {
        val base = layer(0)
        if (base.isEmpty) return null
        val out = pool.obtain(base.width, base.height)
        val c = Canvas(out)
        c.drawBitmap(base.bitmap, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        val visible = layers.values.filter { it.id > 0 && it.opacity > 0 && !it.isEmpty }.sortedWith(compareBy({ it.z }, { it.id }))
        val p = Paint()
        for (l in visible) {
            val (x, y) = absoluteOrigin(l)
            p.alpha = l.opacity
            c.drawBitmap(l.bitmap, Rect(0, 0, l.width, l.height), Rect(x, y, x + l.width, y + l.height), p)
        }
        return out
    }

    fun release(frame: Bitmap) = pool.release(frame)

    companion object {
        /** Masque de canal Guacamole → mode Porter-Duff (null = dessus, mode par défaut). */
        fun mode(mask: Int): PorterDuff.Mode? = when (ChannelMask.of(mask)) {
            ChannelMask.RIN -> PorterDuff.Mode.DST_IN
            ChannelMask.ROUT -> PorterDuff.Mode.DST_OUT
            ChannelMask.IN -> PorterDuff.Mode.SRC_IN
            ChannelMask.ATOP -> PorterDuff.Mode.SRC_ATOP
            ChannelMask.OUT -> PorterDuff.Mode.SRC_OUT
            ChannelMask.RATOP -> PorterDuff.Mode.DST_ATOP
            ChannelMask.XOR -> PorterDuff.Mode.XOR
            ChannelMask.ROVER -> PorterDuff.Mode.DST_OVER
            ChannelMask.SRC -> PorterDuff.Mode.SRC
            ChannelMask.OVER -> null
            ChannelMask.PLUS -> PorterDuff.Mode.ADD
        }

        private fun matrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) = Matrix().apply {
            setValues(floatArrayOf(a.toFloat(), c.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f))
        }
    }
}
