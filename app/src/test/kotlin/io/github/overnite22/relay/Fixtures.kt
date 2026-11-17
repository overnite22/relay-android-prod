package io.github.overnite22.relay

import android.app.Application
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.core.MemoryAccountStore
import io.github.overnite22.relay.core.Prefs
import io.github.overnite22.relay.kit.HttpRequest
import io.github.overnite22.relay.kit.HttpResponse
import io.github.overnite22.relay.kit.HttpTransport
import io.github.overnite22.relay.kit.ServerAccount
import io.github.overnite22.relay.kit.guac.GuacClientCore
import io.github.overnite22.relay.kit.guac.GuacInstruction
import io.github.overnite22.relay.kit.guac.GuacParser
import io.github.overnite22.relay.kit.guac.ScriptedTransport
import java.util.Base64

/** Données fictives pour les tests (aucun serveur réel, aucun secret réel ; adresses de documentation). */
object Fixtures {
    fun session(app: Application, paired: Boolean = true): AppSession {
        val prefs = Prefs(app).apply {
            recentIds = listOf("e-pve1", "e-win")
            biometrics = false
            keepAlive = false
        }
        val account = ServerAccount("https://relay.example.com", "rlt_" + "x".repeat(43))
        val s = AppSession(app, MemoryAccountStore(if (paired) account else null), prefs, FixtureHttp)
        s.tunnelOverride = {
            ScriptedTransport(GuacFixture.script).apply { respond = GuacFixture::fileServer }
        }
        return s
    }
}

/** Faux serveur Relay. */
object FixtureHttp : HttpTransport {
    /** Requêtes reçues (vérifications des tests). */
    val log = java.util.Collections.synchronizedList(ArrayList<HttpRequest>())

    override suspend fun execute(request: HttpRequest): HttpResponse {
        log.add(request)
        val path = request.path
        val m = request.method
        var status = 200
        val body: String = when {
            m == "GET" && path == "/api/me" -> """{"user":"admin@example.com","version":"0.7.0"}"""
            m == "GET" && path == "/api/tree" -> TREE
            m == "GET" && path == "/api/connection-status" -> STATUS
            m == "GET" && path == "/api/credentials" -> CREDENTIALS
            m == "GET" && path == "/api/audit" -> AUDIT
            m == "GET" && path == "/api/workspaces" -> """[{"id":"w-matin","name":"Tour du matin","entryIds":["e-pve1","e-win"]}]"""
            (m == "POST" && path == "/api/workspaces") || (m == "PUT" && path == "/api/workspaces/w-matin") ->
                request.bodyText.replace("\"id\":\"\"", "\"id\":\"w-new\"")
            m == "DELETE" -> { status = 204; "" }
            m == "GET" && path.startsWith("/api/entries/") && path.endsWith("/attachments") ->
                """[{"id":"a1","name":"schema-reseau.pdf","size":48213,"createdAt":"2026-11-10T09:00:00Z"}]"""
            m == "GET" && path.startsWith("/api/entries/") && path.contains("/attachments/") -> "%PDF-1.4 démo"
            m == "POST" && path.startsWith("/api/entries/") && path.endsWith("/attachments") ->
                """{"id":"a2","name":"photo.jpg","size":1200,"createdAt":"2026-11-12T09:00:00Z"}"""
            m == "GET" && path.startsWith("/api/entries/") ->
                """{"id":"e-pve1","folderId":"f-pve","name":"pve-01","kind":"ssh","host":"pve-01.lab.example","port":22,"url":"","username":"root","credentialId":"c-key","settings":{"sftp":true},"notes":"Nœud principal.","favorite":true,"sourceId":"s1","externalId":"","createdAt":"","updatedAt":""}"""
            m == "POST" && path.endsWith("/test-connection") ->
                """{"address":"192.0.2.10:22","ip":"192.0.2.10","status":"reachable","durationMs":4,"checkedAt":"2026-11-12T08:00:00Z"}"""
            m == "POST" && path.endsWith("/connect") -> """{"token":"fixture","protocol":"ssh"}"""
            m == "POST" && path.endsWith("/merge") ->
                """{"id":"e-pve2","folderId":"f-pve","name":"pve-02","kind":"ssh","host":"192.0.2.12","port":22,"url":"","username":"root","credentialId":null,"notes":"","favorite":false,"sourceId":"s1","updatedAt":""}"""
            (m == "POST" && path == "/api/entries") || (m == "PUT" && path.startsWith("/api/entries/")) ->
                """{"id":"e-new","folderId":null,"name":"nouveau","kind":"ssh","host":"192.0.2.50","port":22,"url":"","username":"","credentialId":null,"notes":"","favorite":false,"sourceId":null,"updatedAt":""}"""
            m == "PUT" && path.endsWith("/credentials") && path.startsWith("/api/folders/") -> { status = 204; "" }
            (m == "POST" && path == "/api/folders") || (m == "PUT" && path.startsWith("/api/folders/")) ->
                """{"id":"f-new","parentId":null,"name":"Nouveau","credentials":{}}"""
            m == "POST" && path.endsWith("/reveal") -> """{"value":"Exemple-Secret-42"}"""
            m == "GET" && path.endsWith("/totp") -> """{"code":"492039","remaining":21,"period":30}"""
            (m == "POST" && path == "/api/credentials") || (m == "PUT" && path.startsWith("/api/credentials/")) ->
                """{"id":"c-new","name":"Nouveau","username":"ops","domain":"","notes":"","hasPassword":true,"hasPrivateKey":false,"hasPassphrase":false,"hasTotp":false}"""
            m == "GET" && path == "/api/sources" -> SOURCES
            m == "POST" && (path == "/api/sources/s1/sync" || path == "/api/sources/s1/test") -> """{"ok":true,"message":"Connexion réussie : Proxmox VE 9.2"}"""
            (m == "POST" && path == "/api/sources") || (m == "PUT" && path.startsWith("/api/sources/")) -> SOURCES.removePrefix("[").substringBefore("},{") + "}"
            m == "GET" && path == "/api/duplicates" -> DUPLICATES
            m == "POST" && path == "/api/import/rdm/preview" -> IMPORT_PLAN
            m == "POST" && path == "/api/import/rdm/apply" -> """{"folders":2,"entries":3,"credentials":1,"rootId":"f-import"}"""
            m == "POST" && path == "/api/backups/export" -> "RELAYBACKUP-démo"
            m == "POST" && path == "/api/backups/restore" -> """{"ok":true}"""
            m == "POST" && path == "/api/commands/run" -> commandRun(false)
            m == "GET" && path == "/api/commands/runs/r-demo" -> commandRun(true)
            m == "POST" && path.startsWith("/api/commands/runs/") -> { status = 204; "" }
            m == "GET" && path == "/api/commands/runs" -> "[" + commandRun(true) + "]"
            m == "GET" && path == "/api/commands/saved" -> """[{"id":"s1","name":"Espace disque","command":"df -h /","sudo":false}]"""
            m == "POST" && path == "/api/commands/saved" -> """{"id":"s2","name":"Nouvelle","command":"uptime","sudo":false}"""
            m == "POST" && path == "/api/known-hosts/forget" -> { status = 204; "" }
            m == "GET" && path == "/api/recordings" -> RECORDINGS
            m == "GET" && path == "/api/recordings/r-demo" -> GuacFixture.recording
            m == "POST" && path == "/api/quick-connect" -> """{"token":"fixture","protocol":"ssh"}"""
            else -> { status = 404; """{"error":"introuvable"}""" }
        }
        return HttpResponse(status, body.toByteArray())
    }

    const val TREE = """
    {"folders":[
      {"id":"f-lab","parentId":null,"name":"Laboratoire"},
      {"id":"f-pve","parentId":"f-lab","name":"Proxmox"},
      {"id":"f-win","parentId":null,"name":"Windows","credentials":{"rdp":"c-admin"}}],
     "entries":[
      {"id":"e-pve1","folderId":"f-pve","name":"pve-01","kind":"ssh","host":"pve-01.lab.example","port":22,"url":"","username":"root","credentialId":"c-key","notes":"Nœud principal.\n**Maintenance** le mardi.","favorite":true,"sourceId":"s1","updatedAt":"2026-11-12T08:00:00Z"},
      {"id":"e-pve2","folderId":"f-pve","name":"pve-02","kind":"ssh","host":"192.0.2.12","port":22,"url":"","username":"root","credentialId":"c-key","notes":"","favorite":false,"sourceId":"s1","updatedAt":""},
      {"id":"e-console","folderId":"f-pve","name":"vm-101 (console)","kind":"pve","host":"","port":0,"url":"","username":"","credentialId":null,"notes":"","favorite":false,"sourceId":"s1","updatedAt":""},
      {"id":"e-win","folderId":"f-win","name":"srv-ad-01","kind":"rdp","host":"192.0.2.20","port":3389,"url":"","username":"","credentialId":"c-admin","notes":"Contrôleur de domaine.","favorite":true,"sourceId":null,"updatedAt":""},
      {"id":"e-backup","folderId":"f-win","name":"srv-backup","kind":"rdp","host":"192.0.2.21","port":3389,"url":"","username":"","credentialId":"c-admin","notes":"","favorite":false,"sourceId":null,"updatedAt":""},
      {"id":"e-nas","folderId":"f-lab","name":"nas-01","kind":"web","host":"","port":0,"url":"https://nas.lab.example","username":"admin","credentialId":null,"notes":"","favorite":false,"sourceId":null,"updatedAt":""},
      {"id":"e-root","folderId":null,"name":"bastion","kind":"ssh","host":"bastion.example.com","port":2222,"url":"","username":"ops","credentialId":null,"notes":"","favorite":false,"sourceId":null,"updatedAt":""}]}
    """

    const val STATUS = """
    {"e-pve1":{"address":"pve-01.lab.example:22","ip":"192.0.2.10","status":"reachable","durationMs":3,"checkedAt":"2026-11-12T08:00:00Z"},
     "e-pve2":{"address":"192.0.2.12:22","status":"timeout","durationMs":3000,"checkedAt":"2026-11-12T08:00:00Z"},
     "e-win":{"address":"192.0.2.20:3389","status":"reachable","durationMs":5,"checkedAt":"2026-11-12T08:00:00Z"},
     "e-backup":{"address":"192.0.2.21:3389","status":"refused","durationMs":2,"checkedAt":"2026-11-12T08:00:00Z"}}
    """

    const val CREDENTIALS = """
    [{"id":"c-admin","name":"Administrateur du domaine","username":"Administrator","domain":"LAB","notes":"","hasPassword":true,"hasPrivateKey":false,"hasPassphrase":false,"hasTotp":false},
     {"id":"c-key","name":"Clé SSH personnelle","username":"root","domain":"","notes":"Clé ed25519.","hasPassword":false,"hasPrivateKey":true,"hasPassphrase":true,"hasTotp":false},
     {"id":"c-mfa","name":"Console fournisseur","username":"ops@example.com","domain":"","notes":"","hasPassword":true,"hasPrivateKey":false,"hasPassphrase":false,"hasTotp":true}]
    """

    const val SOURCES = """[{"id":"s1","kind":"proxmox","name":"Cluster labo","url":"https://pve.lab.example:8006","tokenId":"relay@pve!relay","hasSecret":true,"verifyTls":false,"folderId":"f-lab","options":{"guestEntries":true,"nodeEntries":true},"intervalMin":15,"lastSyncAt":"2026-11-12T07:45:00Z","lastStatus":"12 entrées à jour"},{"id":"s2","kind":"netbox","name":"NetBox","url":"https://netbox.example.com","tokenId":"","hasSecret":true,"verifyTls":true,"folderId":null,"options":{},"intervalMin":0,"lastSyncAt":null,"lastStatus":""}]"""

    const val DUPLICATES = """[{"key":"ssh|192.0.2.12|22","label":"192.0.2.12:22","entries":[{"id":"e-pve2","folderId":"f-pve","name":"pve-02","kind":"ssh","host":"192.0.2.12","port":22,"url":"","username":"root","credentialId":null,"notes":"","favorite":false,"sourceId":"s1","updatedAt":""},{"id":"e-old","folderId":null,"name":"ancien-pve-02","kind":"ssh","host":"pve-02.lab.example","port":22,"url":"","username":"","credentialId":null,"notes":"","favorite":false,"sourceId":null,"updatedAt":""}]}]"""

    const val IMPORT_PLAN = """{"folders":[{"path":["Clients"]},{"path":["Clients","Acme"]}],"entries":[{"name":"acme-dc","kind":"rdp","host":"192.0.2.40","port":3389,"path":["Clients","Acme"],"credential":"Admin Acme","inlineSecret":false},{"name":"acme-fw","kind":"ssh","host":"192.0.2.41","port":22,"path":["Clients","Acme"],"credential":"","inlineSecret":true},{"name":"acme-nas","kind":"web","host":"","port":0,"path":["Clients"],"credential":"","inlineSecret":false}],"credentials":[{"name":"Admin Acme","username":"administrator","encryptedSecret":true}],"skipped":[{"name":"Sauvegardes","type":"Extension","reason":"non pris en charge"}],"warnings":["1 mot de passe chiffré par RDM n'a pas pu être importé."]}"""

    const val RECORDINGS = """
    [{"id":"r-demo","name":"pve-01","kind":"ssh","at":"2026-11-12T07:30:00Z","size":48213,"limited":false},
     {"id":"r-old","name":"srv-ad-01","kind":"rdp","at":"2026-11-10T16:05:00Z","size":67108864,"limited":true}]
    """

    const val AUDIT = """
    [{"id":3,"at":"2026-11-12T07:58:00Z","user":"admin@example.com","action":"session.open","target":"pve-01","detail":"ssh"},
     {"id":2,"at":"2026-11-12T07:40:00Z","user":"admin@example.com","action":"device.create","target":"d1","detail":"Android"},
     {"id":1,"at":"2026-11-11T18:00:00Z","user":"admin@example.com","action":"source.sync","target":"Proxmox","detail":"12 entrées"}]
    """

    fun commandRun(finished: Boolean): String {
        val s1 = if (finished) "ok" else "running"
        val s2 = if (finished) "error" else "pending"
        val out = if (finished) " 12:00:01 up 12 days,  3:04,  1 user,  load average: 0.08, 0.05, 0.01\\n" else ""
        val err = if (finished) "délai dépassé" else ""
        val fin = if (finished) "\"2026-11-12T12:00:03Z\"" else "null"
        return """{"id":"r-demo","at":"2026-11-12T12:00:00Z","finishedAt":$fin,"user":"admin@example.com","command":"uptime","sudo":false,"targets":2,"ok":${if (finished) 1 else 0},"failed":${if (finished) 1 else 0},"results":[
          {"entryId":"e-pve1","name":"pve-01","address":"pve-01.lab.example:22","status":"$s1","exitCode":${if (finished) "0" else "null"},"output":"$out","truncated":false,"error":"","durationMs":412,"fingerprint":"SHA256:exemple","newHostKey":false},
          {"entryId":"e-pve2","name":"pve-02","address":"192.0.2.12:22","status":"$s2","exitCode":null,"output":"","truncated":false,"error":"$err","durationMs":30000,"fingerprint":"","newHostKey":false}]}"""
    }
}

/** Session Guacamole scriptée : fond, cadres, texte simulé et image PNG (vérifie le rendu de bout en bout). */
object GuacFixture {
    const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAYAAADlNHIOAAAA0klEQVR4nO3XsQ0CQRAEwc+HGAmYKCADMDD69FMnndnSSGXt9Xi+3t/+r6f/r7/qAes9AADbPQAA2z0AANv9dfrAu/cA4h5A3AOIewBxDyDuAcQ9gLh3iMU9AADbPQAA2z0AANs9gBrg9IF37wHEPYC4BxD3AOIeQNwDiHsAce8Qi3sAALZ7AAC2ewAAtnsANcDpA+/eA4h7AHEPIO4BxD2AuAcQ9wDi3iEW9wAAbPcAAGz3AABs9wBqgNMH3r0HEPcA4h5A3AOIewBxDyDuAcT9B4SClJNl9ZprAAAAAElFTkSuQmCC"

    private fun i(op: String, vararg args: String) = GuacInstruction(op, *args).encoded()

    val script: List<String>
        get() {
            val out = mutableListOf(i("", "fixture-tunnel"))
            val f = StringBuilder(i("size", "0", "1170", "1800"))
            f.append(i("rect", "0", "0", "0", "1170", "1800")).append(i("cfill", "14", "0", "18", "20", "24", "255"))
            f.append(i("rect", "0", "0", "0", "1170", "70")).append(i("cfill", "14", "0", "43", "125", "233", "255"))
            for (row in 0 until 14) {
                val y = 110 + row * 52
                val width = 200 + (row * 137) % 760
                f.append(i("rect", "0", "40", y.toString(), width.toString(), "26"))
                val g = row % 3 == 0
                f.append(i("cfill", "14", "0", if (g) "63" else "200", if (g) "185" else "200", if (g) "80" else "205", "255"))
            }
            f.append(i("start", "0", "900", "1300")).append(i("line", "0", "1100", "1300")).append(i("line", "0", "1000", "1450")).append(i("close", "0"))
            f.append(i("cfill", "14", "0", "229", "112", "0", "255"))
            f.append(i("img", "1", "14", "0", "image/png", "60", "1250"))
            f.append(i("blob", "1", PNG)).append(i("end", "1"))
            f.append(i("copy", "0", "60", "1250", "96", "64", "14", "0", "200", "1250"))
            f.append(i("size", "-1", "16", "16")).append(i("rect", "-1", "0", "0", "16", "16")).append(i("cfill", "14", "-1", "255", "255", "255", "255"))
            f.append(i("cursor", "0", "0", "-1", "0", "0", "16", "16"))
            f.append(i("name", "pve-01 (démo)"))
            f.append(i("filesystem", "0", "Relay"))
            f.append(i("sync", "1"))
            out.add(f.toString())
            return out
        }

    /** Enregistrement : la trame de démonstration, un carré qui traverse l'écran, puis une longue pause. */
    val recording: String
        get() {
            val text = StringBuilder(script.drop(1).joinToString("").replace(i("sync", "1"), i("sync", "1000")))
            for (step in 0 until 30) {
                val x = 40 + step * 35
                text.append(i("rect", "0", (x - 35).toString(), "1000", "60", "60")).append(i("cfill", "14", "0", "18", "20", "24", "255"))
                text.append(i("rect", "0", x.toString(), "1000", "60", "60")).append(i("cfill", "14", "0", "229", "112", "0", "255"))
                text.append(i("sync", (1100 + step * 100).toString()))
            }
            text.append(i("rect", "0", "40", "1100", "400", "40")).append(i("cfill", "14", "0", "63", "185", "80", "255")).append(i("sync", "60000"))
            return text.toString()
        }

    /** Faux système de fichiers : répond aux get (dossiers, fichiers) et accuse les envois. */
    fun fileServer(text: String): List<String> {
        val instructions = runCatching { GuacParser().feed(text) }.getOrElse { return emptyList() }
        val dir = GuacClientCore.DIRECTORY_MIMETYPE
        val folders = mapOf(
            "/" to """{"/Documents":"$dir","/rapport.txt":"text/plain"}""",
            "/Documents" to """{"/Documents/notes.md":"text/markdown"}""",
        )
        val b64 = { s: String -> Base64.getEncoder().encodeToString(s.toByteArray()) }
        val replies = ArrayList<String>()
        for (ins in instructions) {
            when (ins.opcode) {
                "get" -> {
                    val name = ins.string(1)
                    val listing = folders[name]
                    replies += if (listing != null) {
                        i("body", "0", "30", dir, name) + i("blob", "30", b64(listing)) + i("end", "30")
                    } else {
                        i("body", "0", "31", "text/plain", name) + i("blob", "31", b64("Contenu de démonstration de $name\n")) + i("end", "31")
                    }
                }
                "put" -> replies += i("ack", ins.string(1), "OK", "0")
                "blob" -> replies += i("ack", ins.string(0), "OK", "0")
            }
        }
        return replies
    }
}
