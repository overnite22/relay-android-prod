package io.github.overnite22.relay.kit.guac

/** Keysyms X11 utilisés par Guacamole (instruction `key`). */
object Keysym {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val ENTER = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63
    const val DELETE = 0xFFFF
    const val SHIFT_LEFT = 0xFFE1
    const val CONTROL_LEFT = 0xFFE3
    const val ALT_LEFT = 0xFFE9
    const val SUPER_LEFT = 0xFFEB
    const val ALT_RIGHT = 0xFFEA

    fun function(n: Int): Int = 0xFFBE + (n.coerceIn(1, 12) - 1)

    /** Keysym d'un point de code tapé : Latin-1 direct, sinon forme Unicode (0x0100xxxx). */
    fun fromCodePoint(v: Int): Int = when (v) {
        0x0A, 0x0D -> ENTER
        0x09 -> TAB
        0x08, 0x7F -> BACKSPACE
        0x1B -> ESCAPE
        in 0x20..0xFF -> v
        in 0x100..0x10FFFF -> 0x0100_0000 or v
        else -> 0
    }

    /** Keysyms d'un texte inséré, dans l'ordre (chaque caractère donne un appui + relâchement). */
    fun sequence(text: String): List<Int> = text.codePoints().toArray().map(::fromCodePoint).filter { it != 0 }
}

/** Masque des boutons de l'instruction `mouse`. */
object MouseButtons {
    const val NONE = 0
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val SCROLL_UP = 8
    const val SCROLL_DOWN = 16
}
