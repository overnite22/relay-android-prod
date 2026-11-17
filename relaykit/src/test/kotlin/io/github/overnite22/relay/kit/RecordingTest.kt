package io.github.overnite22.relay.kit

import io.github.overnite22.relay.kit.guac.Bytes
import io.github.overnite22.relay.kit.guac.DisplayCommand
import io.github.overnite22.relay.kit.guac.GuacParseException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingTest {
    @Test fun timelineSplitsFramesAndAssemblesImages() {
        var text = ins("size", "0", "800", "600") + ins("rect", "0", "0", "0", "10", "10") + ins("cfill", "14", "0", "1", "2", "3", "255")
        text += ins("sync", "100000")
        text += ins("img", "3", "14", "0", "image/png", "5", "6") + ins("blob", "3", b64("png")) + ins("end", "3") + ins("sync", "100250")
        text += ins("copy", "0", "0", "0", "5", "5", "14", "0", "1", "1") + ins("sync", "160250")
        text += ins("rect", "0", "1", "1", "1", "1")
        val t = RecordingTimeline.decode(text.toByteArray())
        assertEquals(listOf(0, 250, 60250, 60250), t.frames.map { it.time })
        assertEquals(3, t.frames[0].commands.size)
        assertEquals(listOf(DisplayCommand.Image(0, 14, 5, 6, "image/png", Bytes.of("png"))), t.frames[1].commands)
        assertEquals(60250, t.duration)
        assertEquals(0, t.frameIndex(0))
        assertEquals(1, t.frameIndex(300))
        assertEquals(3, t.frameIndex(999_999))
        assertEquals(60.0, t.delay(2, 1.0, false), 0.001)
        assertEquals(1.0, t.delay(2, 1.0, true), 0.001)
        assertEquals(0.125, t.delay(1, 2.0, true), 0.001)
        assertEquals("1:00", RecordingTimeline.clock(60250))
        assertEquals("1:02:05", RecordingTimeline.clock(3_725_000))
    }

    @Test fun decodeSpansChunkBoundaries() {
        // Une image de plus d'un mégaoctet traverse plusieurs morceaux de lecture.
        val payload = java.util.Base64.getEncoder().encodeToString(ByteArray(900_000) { 7 })
        val text = ins("img", "1", "12", "0", "image/png", "0", "0") + ins("blob", "1", payload) + ins("end", "1") + ins("sync", "5")
        val t = RecordingTimeline.decode(text.toByteArray())
        assertEquals(1, t.frames.size)
        assertEquals(900_000, (t.frames[0].commands.first() as DisplayCommand.Image).data.size)
    }

    @Test fun rejectsGarbage() {
        assertThrows(GuacParseException::class.java) { RecordingTimeline.decode("abc;def".toByteArray()) }
        assertThrows(GuacParseException::class.java) { RecordingTimeline.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test fun clientListsAndDownloads() = runTest {
        val stub = StubTransport()
        stub["GET /api/recordings"] = ok(
            """[{"id":"a","name":"pve-01","kind":"ssh","at":"2026-11-10T10:00:00Z","size":10,"limited":false},
                {"id":"b","name":"win-01","kind":"rdp","at":"2026-11-11T10:00:00Z","size":20,"limited":true}]""",
        )
        stub["GET /api/recordings/b"] = ok("4.sync,1.1;")
        stub["DELETE /api/recordings/a"] = status(204)
        val client = RelayClient(account, stub)
        val list = client.recordings()
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(EntryKind.RDP, list[0].kind)
        assertEquals("4.sync,1.1;", client.recordingData("b").toString(Charsets.UTF_8))
        assertEquals("application/octet-stream", stub.last.header("Accept"))
        client.deleteRecording("a")
        assertEquals("DELETE", stub.last.method)
    }
}
