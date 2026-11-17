package io.github.overnite22.relay.kit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test fun runLifecycle() = runTest {
        val stub = StubTransport()
        val running = """{"id":"r1","at":"2026-11-12T10:00:00Z","finishedAt":null,"user":"a@example.com","command":"uptime","sudo":false,"targets":2,"ok":0,"failed":0,"results":[{"entryId":"e1","name":"pve-01","address":"192.0.2.10:22","status":"running","exitCode":null,"output":"","truncated":false,"error":"","durationMs":0,"fingerprint":"","newHostKey":false},{"entryId":"e2","name":"pve-02","address":"192.0.2.12:22","status":"futur","exitCode":null,"output":"","truncated":false,"error":"","durationMs":0,"fingerprint":"","newHostKey":false}]}"""
        stub["POST /api/commands/run"] = ok(running)
        stub["GET /api/commands/runs/r1"] = ok(running.replace("\"finishedAt\":null", "\"finishedAt\":\"2026-11-12T10:00:02Z\""))
        stub["POST /api/commands/runs/r1/cancel"] = status(204)
        stub["GET /api/commands/runs"] = ok("[]")
        stub["GET /api/commands/saved"] = ok("""[{"id":"s1","name":"Disque","command":"df -h","sudo":false}]""")
        stub["POST /api/commands/saved"] = ok("""{"id":"s2","name":"Maj","command":"apt update","sudo":true}""")
        stub["PUT /api/commands/saved/s1"] = ok("""{"id":"s1","name":"Disque","command":"df -hT","sudo":false}""")
        stub["DELETE /api/commands/saved/s1"] = status(204)
        stub["POST /api/known-hosts/forget"] = status(204)
        val client = RelayClient(account, stub)

        val run = client.startRun(RunRequest(listOf("e1", "e2"), "uptime", sudo = false))
        assertFalse(run.finished)
        assertEquals(listOf(RunStatus.RUNNING, RunStatus.UNKNOWN), run.results?.map { it.status })
        assertEquals(setOf("entryIds", "command", "sudo", "timeoutSec", "concurrency"), stub.requests[0].json().keys)
        assertTrue(client.run("r1").finished)
        client.cancelRun("r1")
        client.runHistory()
        assertEquals("limit=50", stub.last.query)
        assertEquals("df -h", client.savedCommands().first().command)
        assertEquals("s2", client.saveCommand(SavedCommand(name = "Maj", command = "apt update", sudo = true)).id)
        assertEquals("df -hT", client.saveCommand(SavedCommand("s1", "Disque", "df -hT", false)).command)
        client.deleteSavedCommand("s1")
        client.forgetHostKey("192.0.2.10:22")
        assertEquals("""{"address":"192.0.2.10:22"}""", stub.last.bodyText)
    }
}
