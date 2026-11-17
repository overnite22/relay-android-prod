package io.github.overnite22.relay.kit.guac

/** Octets comparés par contenu (images, fichiers). */
class Bytes(val data: ByteArray) {
    val size: Int get() = data.size
    override fun equals(other: Any?) = other is Bytes && other.data.contentEquals(data)
    override fun hashCode() = data.contentHashCode()
    override fun toString() = "Bytes(${data.size})"

    companion object {
        val EMPTY = Bytes(ByteArray(0))
        fun of(text: String) = Bytes(text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Opérations d'affichage décodées du protocole Guacamole, exécutées par le rendu (Canvas côté Android).
 * Les calques ≥ 0 sont visibles (0 = écran) ; les calques < 0 sont des tampons hors écran.
 */
sealed interface DisplayCommand {
    data class Size(val layer: Int, val width: Int, val height: Int) : DisplayCommand
    data class Image(val layer: Int, val mask: Int, val x: Int, val y: Int, val mime: String, val data: Bytes) : DisplayCommand
    data class Copy(val src: Int, val sx: Int, val sy: Int, val width: Int, val height: Int, val mask: Int, val dst: Int, val dx: Int, val dy: Int) : DisplayCommand
    data class Transfer(val src: Int, val sx: Int, val sy: Int, val width: Int, val height: Int, val function: Int, val dst: Int, val dx: Int, val dy: Int) : DisplayCommand
    data class Rect(val layer: Int, val x: Int, val y: Int, val width: Int, val height: Int) : DisplayCommand
    data class Start(val layer: Int, val x: Int, val y: Int) : DisplayCommand
    data class Line(val layer: Int, val x: Int, val y: Int) : DisplayCommand
    data class Curve(val layer: Int, val cp1x: Int, val cp1y: Int, val cp2x: Int, val cp2y: Int, val x: Int, val y: Int) : DisplayCommand
    data class Arc(val layer: Int, val x: Int, val y: Int, val radius: Int, val start: Double, val end: Double, val negative: Boolean) : DisplayCommand
    data class Close(val layer: Int) : DisplayCommand
    data class Clip(val layer: Int) : DisplayCommand
    data class Push(val layer: Int) : DisplayCommand
    data class Pop(val layer: Int) : DisplayCommand
    data class Reset(val layer: Int) : DisplayCommand
    data class Identity(val layer: Int) : DisplayCommand
    data class Transform(val layer: Int, val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) : DisplayCommand
    data class Distort(val layer: Int, val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) : DisplayCommand
    data class Cfill(val layer: Int, val mask: Int, val r: Int, val g: Int, val b: Int, val a: Int) : DisplayCommand
    data class Cstroke(val layer: Int, val mask: Int, val cap: Int, val join: Int, val thickness: Int, val r: Int, val g: Int, val b: Int, val a: Int) : DisplayCommand
    data class Lfill(val layer: Int, val mask: Int, val src: Int) : DisplayCommand
    data class Lstroke(val layer: Int, val mask: Int, val cap: Int, val join: Int, val thickness: Int, val src: Int) : DisplayCommand
    data class Dispose(val layer: Int) : DisplayCommand
    data class Move(val layer: Int, val parent: Int, val x: Int, val y: Int, val z: Int) : DisplayCommand
    data class Shade(val layer: Int, val opacity: Int) : DisplayCommand
    data class Cursor(val hotX: Int, val hotY: Int, val src: Int, val sx: Int, val sy: Int, val width: Int, val height: Int) : DisplayCommand
    /** Fin d'une trame : le rendu doit être rafraîchi, puis `sync` renvoyé au serveur. */
    data class Sync(val timestamp: Int) : DisplayCommand
}

/** Modes de composition Guacamole (masque de canal) → opérations Porter-Duff. */
enum class ChannelMask(val raw: Int) {
    RIN(0x1), ROUT(0x2), IN(0x4), ATOP(0x6), OUT(0x8), RATOP(0x9), XOR(0xA), ROVER(0xB), SRC(0xC), OVER(0xE), PLUS(0xF);

    companion object {
        fun of(mask: Int): ChannelMask = entries.firstOrNull { it.raw == mask } ?: OVER
    }
}
