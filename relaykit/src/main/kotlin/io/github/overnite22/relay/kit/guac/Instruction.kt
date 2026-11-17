package io.github.overnite22.relay.kit.guac

/** Instruction du protocole Guacamole : `len.opcode,len.arg,…;` (longueurs en points de code Unicode). */
data class GuacInstruction(val opcode: String, val args: List<String> = emptyList()) {
    constructor(opcode: String, vararg args: String) : this(opcode, args.toList())

    fun encoded(): String {
        val out = StringBuilder()
        (listOf(opcode) + args).forEachIndexed { i, element ->
            if (i > 0) out.append(',')
            out.append(element.codePointCount(0, element.length)).append('.').append(element)
        }
        return out.append(';').toString()
    }

    fun int(index: Int): Int = args.getOrNull(index)?.toIntOrNull() ?: 0
    fun double(index: Int): Double = args.getOrNull(index)?.toDoubleOrNull() ?: 0.0
    fun string(index: Int): String = args.getOrNull(index) ?: ""
}

class GuacParseException(reason: String) : Exception(reason)

/** Analyseur incrémental : accepte des fragments et renvoie les instructions complètes. */
class GuacParser {
    private val pending = StringBuilder()

    fun feed(text: String): List<GuacInstruction> {
        pending.append(text)
        val s = pending
        val n = s.length
        val out = ArrayList<GuacInstruction>()
        val elements = ArrayList<String>()
        var cursor = 0
        var instructionStart = 0
        parse@ while (cursor < n) {
            // Longueur.
            var length = 0
            var digits = 0
            while (cursor < n) {
                val c = s[cursor]
                if (c !in '0'..'9') break
                length = length * 10 + (c - '0')
                digits++
                cursor++
                if (digits > 9) throw GuacParseException("longueur trop grande")
            }
            if (cursor >= n) break
            if (digits == 0 || s[cursor] != '.') throw GuacParseException("séparateur de longueur attendu")
            cursor++
            // Valeur : `length` points de code (une paire de substitution compte pour un).
            var end = cursor
            var count = 0
            while (count < length) {
                if (end >= n) break@parse
                val c = s[end++]
                if (Character.isHighSurrogate(c)) {
                    if (end >= n) break@parse
                    if (Character.isLowSurrogate(s[end])) end++
                }
                count++
            }
            if (end >= n) break
            val value = s.substring(cursor, end)
            cursor = end
            val terminator = s[cursor++]
            elements.add(value)
            when (terminator) {
                ',' -> continue@parse
                ';' -> {
                    out.add(GuacInstruction(elements[0], elements.subList(1, elements.size).toList()))
                    elements.clear()
                    instructionStart = cursor
                }
                else -> throw GuacParseException("terminaison inattendue")
            }
        }
        pending.delete(0, instructionStart)
        if (pending.length > 32 shl 20) throw GuacParseException("instruction trop longue")
        return out
    }
}
