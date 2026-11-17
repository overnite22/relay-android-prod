package io.github.overnite22.relay.kit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayClientTest {
    @Test fun requestsCarryTokenAndCsrfHeader() = runTest {
        val stub = StubTransport()
        stub["GET /api/me"] = ok("""{"user":"moi@example.com","version":"0.6"}""")
        val me = RelayClient(account, stub).me()
        assertEquals(ServerInfo("moi@example.com", "0.6"), me)
        val req = stub.requests[0]
        assertEquals("Bearer ${account.token}", req.header("Authorization"))
        assertEquals("1", req.header("X-Relay"))
        assertEquals("https://relay.example.com/api/me", req.url)
    }

    @Test fun treeDecodingWithUnknownKindAndSearch() = runTest {
        val stub = StubTransport()
        stub["GET /api/tree"] = ok(
            """
            {"folders":[{"id":"f1","parentId":null,"name":"Lab","createdAt":"2026-11-11T10:00:00Z","updatedAt":"2026-11-11T10:00:00Z"},
                        {"id":"f2","parentId":"f1","name":"Proxmox","createdAt":"","updatedAt":""}],
             "entries":[{"id":"e1","folderId":"f2","name":"pve1","kind":"ssh","host":"pve1.lab.example","port":22,"url":"","username":"root",
                         "credentialId":"c1","settings":{"sftp":true},"notes":"Nœud 1","favorite":true,"sourceId":null,"externalId":"","createdAt":"","updatedAt":"2026-11-11T10:00:00.5Z"},
                        {"id":"e2","folderId":null,"name":"Futur","kind":"telnet","host":"x","port":23,"url":"","username":"","credentialId":null,
                         "settings":{},"notes":"","favorite":false,"sourceId":null,"externalId":"","createdAt":"","updatedAt":""}]}
            """,
        )
        val tree = RelayClient(account, stub).tree()
        assertEquals(2, tree.entries.size)
        assertEquals(emptyMap<String, String?>(), tree.folders[0].credentials)
        assertEquals(EntryKind.OTHER, tree.entries[1].kind)
        assertEquals("pve1.lab.example:22", tree.entries[0].target)
        assertEquals(listOf("Lab", "Proxmox"), tree.path("f2").map { it.name })
        assertEquals(listOf("e1"), tree.search("pve nœud").map { it.id })
        assertEquals(listOf("e2"), tree.entries(null).map { it.id })
        assertNotNull(RelayDate.parse(tree.entries[0].updatedAt))
        assertNotNull(RelayDate.parse("2026-11-11T10:00:00Z"))
    }

    @Test fun sortingIsCaseAndAccentFriendly() {
        val t = Tree(entries = listOf("zeta", "Écran", "alpha", "Beta").mapIndexed { i, n -> Entry("$i", null, n, EntryKind.SSH) })
        assertEquals(listOf("alpha", "Beta", "Écran", "zeta"), t.entries(null).map { it.name })
    }

    @Test fun errorsAreMapped() = runTest {
        val stub = StubTransport()
        stub["GET /api/tree"] = status(401, """{"error":"Authentification requise"}""")
        stub["GET /api/credentials"] = status(500, """{"error":"erreur interne"}""")
        stub["POST /api/entries/e1/connect"] = status(403, """{"error":"origine refusée"}""")
        stub["GET /api/workspaces"] = status(502, "<html>")
        val client = RelayClient(account, stub)
        suspend fun err(block: suspend () -> Unit): Throwable = try {
            block(); fail("pas d'erreur"); throw AssertionError()
        } catch (e: RelayError) {
            e
        }
        assertEquals(RelayError.Unauthorized, err { client.tree() })
        assertEquals(RelayError.Server("erreur interne"), err { client.credentials() })
        assertEquals(RelayError.Forbidden("origine refusée"), err { client.connect("e1", SessionOptions(1, 1)) })
        assertEquals(RelayError.Server("Erreur serveur (HTTP 502)."), err { client.workspaces() })
        assertTrue(err { client.audit() } is RelayError.Transport)
    }

    @Test fun connectBodyAndTunnelUrl() = runTest {
        val stub = StubTransport()
        stub["POST /api/entries/e%201/connect"] = ok("""{"token":"abc","protocol":"rdp"}""")
        val client = RelayClient(account, stub)
        val ticket = client.connect("e 1", SessionOptions(1200, 800, 192, "Europe/Paris", record = false))
        assertEquals(SessionTicket("abc", "rdp"), ticket)
        val json = stub.requests[0].json()
        assertEquals(setOf("width", "height", "dpi", "timezone", "record", "native"), json.keys)
        assertEquals(192, json["dpi"]!!.jsonPrimitive.int)
        assertEquals("wss://relay.example.com/api/tunnel?token=a%20b", client.tunnelUrl("a b"))
        assertEquals("ws://localhost:8080/api/tunnel?token=x", RelayClient(ServerAccount("http://localhost:8080", "t"), stub).tunnelUrl("x"))
    }

    @Test fun revealAndTotp() = runTest {
        val stub = StubTransport()
        stub["POST /api/credentials/c1/reveal"] = ok("""{"value":"s3cret"}""")
        stub["GET /api/credentials/c1/totp"] = ok("""{"code":"123456","remaining":12,"period":30}""")
        val client = RelayClient(account, stub)
        assertEquals("s3cret", client.reveal("c1", SecretField.PASSWORD))
        assertEquals("""{"field":"password"}""", stub.requests[0].bodyText)
        assertEquals(TotpCode("123456", 12, 30), client.totp("c1"))
    }

    @Test fun quickConnectEncodesAllServerFields() = runTest {
        val stub = StubTransport()
        stub["POST /api/quick-connect"] = ok("""{"token":"t","protocol":"ssh"}""")
        val req = QuickConnectRequest.of(EntryKind.SSH, "h", 22, "u", options = SessionOptions(10, 20, 96, "UTC"))
        RelayClient(account, stub).quickConnect(req)
        val json = stub.last.json()
        assertEquals(setOf("kind", "host", "port", "username", "domain", "password", "width", "height", "dpi", "timezone", "record"), json.keys)
        assertEquals(JsonPrimitive("ssh"), json["kind"])
        RelayClient(account, stub).quickConnect(req.copy(credentialId = "c1"))
        assertEquals(JsonPrimitive("c1"), stub.last.json()["credentialId"])
    }

    @Test fun favoriteKeepsAdvancedSettings() = runTest {
        val stub = StubTransport()
        val entry = """{"id":"e1","folderId":null,"name":"srv","kind":"rdp","host":"h","port":3389,"url":"","username":"","credentialId":null,"settings":{"security":"nla"},"notes":"","favorite":false,"sourceId":null,"externalId":"x","createdAt":"","updatedAt":""}"""
        stub["GET /api/entries/e1"] = ok(entry)
        stub["PUT /api/entries/e1"] = ok(entry.replace("\"favorite\":false", "\"favorite\":true"))
        val client = RelayClient(account, stub)
        val updated = client.setFavorite(Entry("e1", null, "srv", EntryKind.RDP), true)
        assertTrue(updated.favorite)
        val body = stub.last.json()
        assertEquals(JsonPrimitive(true), body["favorite"])
        assertTrue("settings" in body && "id" !in body && "externalId" !in body && "sourceId" !in body)
    }
}

class WorkspaceTest {
    @Test fun folderCredentialsAndWorkspaces() = runTest {
        val folder = RelayJson.default.decodeFromString(Folder.serializer(), """{"id":"f","parentId":null,"name":"Lab","credentials":{"ssh":"c1","rdp":null}}""")
        assertEquals("c1", folder.credentials["ssh"])
        assertTrue(folder.credentials.containsKey("rdp") && folder.credentials["rdp"] == null)
        val stub = StubTransport()
        stub["GET /api/workspaces"] = ok("""[{"id":"w1","name":"Matin","entryIds":["e1","e2"]}]""")
        stub["POST /api/workspaces"] = ok("""{"id":"w2","name":"Soir","entryIds":["e3"]}""")
        stub["DELETE /api/workspaces/w1"] = status(204)
        val client = RelayClient(account, stub)
        assertEquals(listOf("e1", "e2"), client.workspaces().first().entryIds)
        assertEquals("w2", client.saveWorkspace(Workspace(name = "Soir", entryIds = listOf("e3"))).id)
        client.deleteWorkspace("w1")
        assertEquals("DELETE", stub.last.method)
        assertTrue(EntryKind.PVE.opensNatively)
        assertTrue(!EntryKind.PVE.isMonitored)
    }
}
