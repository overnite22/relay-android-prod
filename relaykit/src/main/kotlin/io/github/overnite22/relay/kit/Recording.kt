package io.github.overnite22.relay.kit

import io.github.overnite22.relay.kit.guac.DisplayCommand
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacError
import io.github.overnite22.relay.kit.guac.GuacParseException
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable

/** Enregistrement de session conservé par le serveur (propre à l'utilisateur). */
@Serializable
data class Recording(
    val id: String,
    val name: String,
    val kind: EntryKind,
    val at: String,
    val size: Long,
    val limited: Boolean = false,
)

/** Chronologie d'un enregistrement : trames d'affichage délimitées par `sync`. */
class RecordingTimeline(val frames: List<Frame>) {
    /** [time] : millisecondes depuis le début de l'enregistrement. */
    class Frame(val time: Int, val commands: List<DisplayCommand>)

    /** Durée totale en millisecondes. */
    val duration: Int get() = frames.lastOrNull()?.time ?: 0

    /** Indice de la dernière trame dont l'heure est ≤ [time]. */
    fun frameIndex(time: Int): Int {
        if (frames.isEmpty()) return -1
        if (frames[0].time > time) return 0
        var low = 0
        var high = frames.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (frames[mid].time <= time) low = mid else high = mid - 1
        }
        return low
    }

    /** Attente réelle (secondes) avant la trame [index], pauses longues raccourcies si demandé. */
    fun delay(index: Int, speed: Double, skipIdle: Boolean): Double {
        if (index <= 0 || index >= frames.size) return 0.0
        var gap = (frames[index].time - frames[index - 1].time) / 1000.0
        if (skipIdle) gap = minOf(gap, 1.0)
        return maxOf(0.0, gap / maxOf(speed, 0.1))
    }

    companion object {
        /** Décode le flux brut ; les instructions après le dernier `sync` forment une trame finale. */
        fun decode(data: ByteArray): RecordingTimeline {
            val core = GuacClientCore()
            val frames = ArrayList<Frame>()
            var pending = ArrayList<DisplayCommand>()
            var origin: Int? = null
            var last = 0
            core.onDisplay = { command ->
                if (command is DisplayCommand.Sync) {
                    val start = origin ?: command.timestamp
                    origin = start
                    last = maxOf(last, command.timestamp - start)
                    frames.add(Frame(last, pending))
                    pending = ArrayList()
                } else {
                    pending.add(command)
                }
            }
            var failure: GuacError? = null
            core.onError = { failure = it }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Lecture par morceaux pour limiter les copies sur les gros fichiers.
            val buffer = CharArray(1 shl 20)
            try {
                InputStreamReader(ByteArrayInputStream(data), decoder).use { reader ->
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        core.receive(String(buffer, 0, n))
                        if (failure != null) throw GuacParseException("flux illisible")
                    }
                }
            } catch (_: CharacterCodingException) {
                throw GuacParseException("encodage")
            }
            if (pending.isNotEmpty()) frames.add(Frame(last, pending))
            return RecordingTimeline(frames)
        }

        /** Format « m:ss » ou « h:mm:ss ». */
        fun clock(milliseconds: Int): String {
            val total = maxOf(0, milliseconds / 1000)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
