package io.github.overnite22.relay.kit

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementTest {
    @Test fun entryRoundTripKeepsAdvancedSettings() = runTest {
        val stub = StubTransport()
        val entry = """{"id":"e1","folderId":"f1","name":"srv","kind":"rdp","host":"192.0.2.20","port":3389,"url":"","username":"","credentialId":null,"settings":{"security":"nla","ignoreCert":true,"resize":"display-update"},"notes":"n","favorite":false,"sourceId":null,"externalId":"","createdAt":"","updatedAt":""}"""
        stub["GET /api/entries/e1"] = ok(entry)
        stub["PUT /api/entries/e1"] = ok(entry)
        stub["POST /api/entries"] = status(201, entry)
        stub["DELETE /api/entries/e1"] = status(204)
        val c = RelayClient(account, stub)
        val input = c.entryInput("e1")
        assertEquals(JsonPrimitive(true), input.settings["ignoreCert"])
        c.saveEntry(input.copy(name = "srv-renommé"), "e1")
        val body = stub.last.json()
        assertEquals(setOf("folderId", "name", "kind", "host", "port", "url", "username", "credentialId", "settings", "notes", "favorite"), body.keys)
        assertEquals(JsonPrimitive("nla"), body["settings"]!!.jsonObject["security"])
        assertTrue(body["credentialId"].isJsonNull())
        c.saveEntry(EntryInput(folderId = null, name = "neuf", kind = EntryKind.SSH, host = "h", port = 22))
        assertEquals("POST", stub.last.method)
        assertTrue(stub.last.json()["folderId"].isJsonNull())
        c.deleteEntry("e1")
    }

    @Test fun foldersAndInheritedCredentials() = runTest {
        val stub = StubTransport()
        stub["POST /api/folders"] = status(201, """{"id":"f2","parentId":null,"name":"Neuf","credentials":{}}""")
        stub["PUT /api/folders/f2"] = ok("""{"id":"f2","parentId":"f1","name":"Renommé"}""")
        stub["PUT /api/folders/f2/credentials"] = status(204)
        stub["DELETE /api/folders/f2"] = status(204)
        val c = RelayClient(account, stub)
        c.saveFolder("Neuf", null)
        assertTrue(stub.last.json()["parentId"].isJsonNull())
        assertEquals("f1", c.saveFolder("Renommé", "f1", "f2").parentId)
        c.setFolderCredentials("f2", mapOf("ssh" to "c1", "rdp" to null))
        val creds = stub.last.json()["credentials"]!!.jsonObject
        assertEquals(JsonPrimitive("c1"), creds["ssh"])
        assertTrue(creds["rdp"].isJsonNull())
        c.deleteFolder("f2")
    }

    @Test fun credentialSecretsAreOnlySentWhenChanged() = runTest {
        val stub = StubTransport()
        val cred = """{"id":"c1","name":"Admin","username":"root","domain":"","notes":"","hasPassword":true,"hasPrivateKey":false,"hasPassphrase":false,"hasTotp":false}"""
        stub["PUT /api/credentials/c1"] = ok(cred)
        stub["POST /api/credentials"] = status(201, cred)
        val c = RelayClient(account, stub)
        c.saveCredential(CredentialInput(name = "Admin", username = "root"), "c1")
        assertEquals(setOf("name", "username", "domain", "notes"), stub.last.json().keys)
        c.saveCredential(CredentialInput(name = "Admin", username = "root", password = "x", totp = ""))
        assertEquals(JsonPrimitive(""), stub.last.json()["totp"])
    }

    @Test fun attachmentsSourcesDuplicatesImportBackup() = runTest {
        val stub = StubTransport()
        val source = """{"id":"s1","kind":"proxmox","name":"Cluster","url":"https://pve.example.com:8006","tokenId":"relay@pve!relay","hasSecret":true,"verifyTls":false,"folderId":"f","options":{"guestEntries":true},"intervalMin":15,"lastSyncAt":null,"lastStatus":"OK","createdAt":"","updatedAt":""}"""
        stub["GET /api/entries/e1/attachments"] = ok("""[{"id":"a1","name":"schéma.pdf","size":10,"createdAt":"2026-11-12T10:00:00Z"}]""")
        stub["POST /api/entries/e1/attachments"] = status(201, """{"id":"a2","name":"x.txt","size":1,"createdAt":"2026-11-12T10:00:00Z"}""")
        stub["GET /api/entries/e1/attachments/a1"] = ok("PDF")
        stub["GET /api/sources"] = ok("[$source]")
        stub["POST /api/sources/s1/sync"] = ok("""{"ok":true,"message":"12 entrées"}""")
        stub["PUT /api/sources/s1"] = ok(source)
        stub["GET /api/duplicates"] = ok("""[{"key":"ssh|192.0.2.1|22","label":"192.0.2.1:22","entries":[]}]""")
        stub["POST /api/entries/e1/merge"] = ok("""{"id":"e1","folderId":null,"name":"srv","kind":"ssh","host":"h","port":22,"url":"","username":"","credentialId":null,"notes":"","favorite":false,"sourceId":null,"updatedAt":""}""")
        stub["POST /api/import/rdm/preview"] = ok("""{"folders":[{"path":["A"]}],"entries":[{"name":"x","kind":"ssh","host":"h","port":22,"url":"","username":"","path":["A"],"credential":"","inlineSecret":false,"rdmType":"SSHShell"}],"credentials":[],"skipped":[{"name":"VBR","type":"Extension","reason":"non pris en charge"}],"warnings":[]}""")
        stub["POST /api/import/rdm/apply"] = ok("""{"folders":1,"entries":1,"credentials":0,"rootId":"r"}""")
        stub["POST /api/backups/export"] = ok("RELAYBACKUP")
        stub["POST /api/backups/restore"] = ok("""{"ok":true}""")
        val c = RelayClient(account, stub)
        assertEquals("schéma.pdf", c.attachments("e1").first().name)
        c.addAttachment("e1", "x.txt", "x".toByteArray())
        assertEquals("name=x.txt", stub.last.query)
        assertEquals("application/octet-stream", stub.last.header("Content-Type"))
        assertArrayEquals("PDF".toByteArray(), c.attachmentData("e1", "a1"))
        val sources = c.sources()
        assertEquals("Proxmox VE", sources.first().kindTitle)
        assertTrue(c.syncSource("s1").ok)
        c.saveSource(SyncSourceInput.of(sources[0]).copy(intervalMin = 30), "s1")
        assertNull("secret inchangé non envoyé", stub.last.json()["secret"])
        assertEquals(JsonPrimitive(30), stub.last.json()["intervalMin"])
        assertEquals("192.0.2.1:22", c.duplicates().first().label)
        c.merge("e1", listOf("e2"))
        assertEquals("""{"ids":["e2"]}""", stub.last.bodyText)
        assertEquals("VBR", c.previewImport("<xml/>".toByteArray()).skipped.first().name)
        assertEquals(1, c.applyImport("<xml/>".toByteArray(), "Import RDM", "c1", "").entries)
        assertTrue(stub.last.query!!.contains("sshDefault=c1"))
        assertTrue(stub.last.query!!.contains("folder=Import%20RDM"))
        val archive = c.exportBackup("secret")
        assertArrayEquals("RELAYBACKUP".toByteArray(), archive)
        c.restoreBackup(archive, "pässe")
        assertEquals(Base64.getEncoder().encodeToString("pässe".toByteArray()), stub.last.header("X-Backup-Password"))
    }
}
