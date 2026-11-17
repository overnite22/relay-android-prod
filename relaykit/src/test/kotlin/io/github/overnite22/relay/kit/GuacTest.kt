package io.github.overnite22.relay.kit

import io.github.overnite22.relay.kit.guac.Bytes
import io.github.overnite22.relay.kit.guac.DisplayCommand
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacError
import io.github.overnite22.relay.kit.guac.GuacInstruction
import io.github.overnite22.relay.kit.guac.GuacParseException
import io.github.overnite22.relay.kit.guac.GuacParser
import io.github.overnite22.relay.kit.guac.GuacState
import io.github.overnite22.relay.kit.guac.Keysym
import io.github.overnite22.relay.kit.guac.MouseButtons
import io.github.overnite22.relay.kit.guac.ScriptedTransport
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())
fun ins(op: String, vararg args: String) = GuacInstruction(op, *args).encoded()

class InstructionTest {
    @Test fun encodeCountsCodePoints() {
        assertEquals("3.key,3.233,1.1;", GuacInstruction("key", "233", "1").encoded())
        assertEquals("4.name,3.é€😀;", GuacInstruction("name", "é€😀").encoded())
        assertEquals("0.,4.ping,2.12;", GuacInstruction("", "ping", "12").encoded())
    }

    @Test fun parseSeveralInstructionsAndFragments() {
        val p = GuacParser()
        assertEquals(listOf(GuacInstruction("size", "0", "1280", "720")), p.feed("4.size,1.0,4.1280,3.720;4.sync,2."))
        assertEquals(listOf(GuacInstruction("sync", "42"), GuacInstruction("nop")), p.feed("42;3.nop;"))
        assertEquals(emptyList<GuacInstruction>(), p.feed("4.name,3.é€😀"))
        assertEquals(listOf(GuacInstruction("name", "é€😀")), p.feed(";"))
    }

    @Test fun surrogatePairSplitAcrossFragments() {
        val p = GuacParser()
        val emoji = "😀"
        assertEquals(emptyList<GuacInstruction>(), p.feed("4.name,1." + emoji[0]))
        assertEquals(listOf(GuacInstruction("name", emoji)), p.feed(emoji[1] + ";"))
    }

    @Test fun parseRejectsMalformedInput() {
        assertThrows(GuacParseException::class.java) { GuacParser().feed("abc;") }
        assertThrows(GuacParseException::class.java) { GuacParser().feed("3.nop:") }
    }

    @Test fun accessors() {
        val i = GuacInstruction("arc", "0", "10", "20", "5", "1.5", "3", "1")
        assertEquals(10, i.int(1))
        assertEquals(1.5, i.double(4), 0.0)
        assertEquals(0, i.int(99))
        assertEquals("0", i.string(0))
    }

    @Test fun keysyms() {
        assertEquals(0x61, Keysym.fromCodePoint('a'.code))
        assertEquals(0xE9, Keysym.fromCodePoint('é'.code))
        assertEquals(0x0100_20AC, Keysym.fromCodePoint('€'.code))
        assertEquals(0x0101_F600, Keysym.sequence("😀").single())
        assertEquals(Keysym.ENTER, Keysym.fromCodePoint('\n'.code))
        assertEquals(listOf(0x6C, 0x73, Keysym.ENTER), Keysym.sequence("ls\n"))
        assertEquals(0xFFC9, Keysym.function(12))
        assertEquals(0xFFBE, Keysym.function(1))
    }
}

class Box {
    val sent = ArrayList<String>()
    val display = ArrayList<DisplayCommand>()
    val states = ArrayList<GuacState>()
    val errors = ArrayList<GuacError>()
    val clipboard = ArrayList<String>()
}

fun makeCore(): Pair<GuacClientCore, Box> {
    val core = GuacClientCore()
    val box = Box()
    core.onSend = { box.sent.add(it) }
    core.onDisplay = { box.display.add(it) }
    core.onState = { box.states.add(it) }
    core.onError = { box.errors.add(it) }
    core.onClipboard = { box.clipboard.add(it) }
    return core to box
}

class ClientCoreTest {
    @Test fun handshakeStatesAndSync() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,8.tunnel-1;")
        assertEquals(GuacState.WAITING, core.state)
        assertEquals("tunnel-1", core.tunnelId)
        core.receive("4.size,1.0,3.800,3.600;4.sync,2.17;")
        assertEquals(GuacState.CONNECTED, core.state)
        assertEquals(listOf(DisplayCommand.Size(0, 800, 600), DisplayCommand.Sync(17)), box.display)
        core.acknowledgeSync(17)
        assertEquals("4.sync,2.17;", box.sent.last())
        assertEquals(listOf(GuacState.CONNECTING, GuacState.WAITING, GuacState.CONNECTED), box.states)
    }

    @Test fun imageStreamIsAssembledAndAcked() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;")
        core.receive(ins("img", "3", "14", "0", "image/png", "10", "20") + ins("blob", "3", b64("hello ")))
        core.receive(ins("blob", "3", b64("world")) + ins("end", "3"))
        assertEquals(DisplayCommand.Image(0, 14, 10, 20, "image/png", Bytes.of("hello world")), box.display.last())
        assertEquals(2, box.sent.count { it.startsWith("3.ack") })
        assertEquals("3.ack,1.3,2.OK,1.0;", box.sent.last())
    }

    @Test fun clipboardFromServer() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;")
        core.receive(ins("clipboard", "5", "text/plain") + ins("blob", "5", b64("bonjour")) + ins("end", "5"))
        assertEquals(listOf("bonjour"), box.clipboard)
    }

    @Test fun errorAndDisconnect() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,11.relay-error;5.error,14.Jeton invalide,3.769;")
        assertEquals(listOf(GuacError("Jeton invalide", 769)), box.errors)
        assertEquals(GuacState.DISCONNECTED, core.state)
        assertTrue(box.errors[0].description.contains("Non autorisé"))
    }

    @Test fun inputsOnlyWhenConnected() {
        val (core, box) = makeCore()
        core.sendMouse(1, 2, MouseButtons.LEFT)
        assertTrue(box.sent.isEmpty())
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;")
        core.sendMouse(1, 2, MouseButtons.LEFT)
        core.tapKey(Keysym.ENTER)
        core.type("é")
        core.sendClipboard("abc")
        assertEquals(
            listOf(
                "5.mouse,1.1,1.2,1.1;", "3.key,5.65293,1.1;", "3.key,5.65293,1.0;", "3.key,3.233,1.1;", "3.key,3.233,1.0;",
                "9.clipboard,1.0,10.text/plain;", "4.blob,1.0,4.YWJj;", "3.end,1.0;",
            ),
            box.sent.filter { !it.startsWith("4.sync") },
        )
        core.disconnect()
        assertEquals("10.disconnect;", box.sent.last())
        assertEquals(GuacState.DISCONNECTED, core.state)
    }

    @Test fun unsupportedStreamsAreRefused() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;5.audio,1.2,9.audio/l16;4.file,1.4,10.text/plain,5.a.txt;")
        assertEquals(1, box.sent.count { it.contains("Unsupported") })
        assertTrue("les fichiers poussés sont acceptés", box.sent.contains("3.ack,1.4,2.OK,1.0;"))
    }

    @Test fun drawingInstructions() {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;")
        core.receive("4.rect,1.0,1.1,1.2,2.30,2.40;5.cfill,2.14,1.0,3.255,1.0,1.0,3.255;4.copy,2.-1,1.0,1.0,2.10,2.10,2.14,1.0,1.5,1.6;6.cursor,1.1,1.2,2.-2,1.0,1.0,2.16,2.16;7.dispose,2.-1;")
        assertEquals(
            listOf(
                DisplayCommand.Rect(0, 1, 2, 30, 40),
                DisplayCommand.Cfill(0, 14, 255, 0, 0, 255),
                DisplayCommand.Copy(-1, 0, 0, 10, 10, 14, 0, 5, 6),
                DisplayCommand.Cursor(1, 2, -2, 0, 0, 16, 16),
                DisplayCommand.Dispose(-1),
            ),
            box.display.drop(1),
        )
    }

    @Test fun scriptedTransportReplaysAndRecordsSends() {
        val transport = ScriptedTransport(listOf("0.,1.t;", "4.sync,1.5;"))
        val done = CountDownLatch(1)
        val received = Collections.synchronizedList(ArrayList<String>())
        transport.onText = { text ->
            received.add(text)
            if (text.startsWith("4.sync")) done.countDown()
        }
        transport.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("0.,1.t;", "4.sync,1.5;"), received.toList())
        transport.send("3.nop;")
        assertEquals(listOf("3.nop;"), transport.sent)
    }
}
