package io.github.overnite22.relay.kit

import io.github.overnite22.relay.kit.guac.Bytes
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacError
import io.github.overnite22.relay.kit.guac.ReceivedFile
import io.github.overnite22.relay.kit.guac.RemoteItem
import io.github.overnite22.relay.kit.guac.RemoteObject
import io.github.overnite22.relay.kit.guac.RemotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    private fun connected(): Pair<GuacClientCore, Box> {
        val (core, box) = makeCore()
        core.connecting()
        core.receive("0.,1.t;4.sync,1.0;")
        box.sent.clear()
        return core to box
    }

    @Test fun listingDirectory() {
        val (core, box) = connected()
        var fsObject = -1
        core.onFilesystem = { index, _ -> fsObject = index }
        core.receive(ins("filesystem", "0", "SFTP"))
        assertEquals(0, fsObject)
        var result: Result<RemoteObject>? = null
        core.requestObject(0, "/home") { result = it }
        assertEquals("3.get,1.0,5./home;", box.sent.last())
        val json = """{"/home/b.txt":"text/plain","/home/a":"application/vnd.glyptodon.guacamole.stream-index+json","/home/A10.log":"text/plain","/home/a2.log":"text/plain"}"""
        core.receive(ins("body", "0", "7", GuacClientCore.DIRECTORY_MIMETYPE, "/home"))
        assertEquals("3.ack,1.7,2.OK,1.0;", box.sent.last())
        core.receive(ins("blob", "7", b64(json)) + ins("end", "7"))
        val value = result!!.getOrThrow()
        assertEquals(GuacClientCore.DIRECTORY_MIMETYPE, value.mimetype)
        val items = RemoteItem.parseIndex(value.data.data)
        assertEquals(listOf("a", "a2.log", "A10.log", "b.txt"), items.map { it.name })
        assertTrue(items[0].isDirectory)
    }

    @Test fun uploadWaitsForEachAck() {
        val (core, box) = connected()
        val data = ByteArray(GuacClientCore.BLOB_SIZE + 10) { 1 }
        val progress = ArrayList<Int>()
        var done: Result<Unit>? = null
        core.putObject(0, "/tmp/x.bin", "application/octet-stream", data, { progress.add(it) }) { done = it }
        assertEquals(listOf("3.put,1.0,1.0,24.application/octet-stream,10./tmp/x.bin;"), box.sent)
        core.receive(ins("ack", "0", "OK", "0"))
        assertTrue(box.sent.last().startsWith("4.blob,1.0,"))
        assertEquals(2, box.sent.size)
        core.receive(ins("ack", "0", "OK", "0"))
        assertEquals(3, box.sent.size)
        core.receive(ins("ack", "0", "OK", "0"))
        assertEquals("3.end,1.0;", box.sent.last())
        assertEquals(listOf(GuacClientCore.BLOB_SIZE, GuacClientCore.BLOB_SIZE + 10), progress)
        assertTrue("envoi terminé", done!!.isSuccess)
    }

    @Test fun uploadRefused() {
        val (core, _) = connected()
        var done: Result<Unit>? = null
        core.putObject(0, "/root/x", "text/plain", "x".toByteArray()) { done = it }
        core.receive(ins("ack", "0", "Permission denied", "771"))
        assertEquals("Permission denied", (done!!.exceptionOrNull() as GuacError).message)
    }

    @Test fun serverPushedFileAndDisconnectFailsTransfers() {
        val (core, box) = connected()
        var received: ReceivedFile? = null
        core.onFileReceived = { received = it }
        core.receive(ins("file", "4", "text/plain", "rapport.txt") + ins("blob", "4", b64("bonjour")) + ins("end", "4"))
        assertEquals(ReceivedFile("rapport.txt", "text/plain", Bytes.of("bonjour")), received)
        assertTrue(box.sent.contains("3.ack,1.4,2.OK,1.0;"))
        var pending: Result<RemoteObject>? = null
        core.requestObject(0, "/") { pending = it }
        core.tunnelClosed()
        assertTrue("requête abandonnée", pending!!.isFailure)
    }

    @Test fun paths() {
        assertEquals("/home", RemotePath.parent("/home/user/"))
        assertEquals("/", RemotePath.parent("/home"))
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("/a", RemotePath.join("/", "a"))
        assertEquals("/home/a", RemotePath.join("/home", "a"))
        assertEquals("a.txt", RemotePath.basename("/home/a.txt"))
        assertEquals("user", RemotePath.basename("/home/user/"))
    }
}
