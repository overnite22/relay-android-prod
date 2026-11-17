package io.github.overnite22.relay

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.overnite22.relay.kit.guac.ScriptedTransport
import io.github.overnite22.relay.sessions.SessionController
import io.github.overnite22.relay.sessions.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "fr-rFR-w411dp-h891dp-xxhdpi")
class FeaturesTest : UiTestBase() {
    private fun connected(c: SessionController? = app.presentedSession) {
        compose.waitUntil(10_000) { settle(50); (c ?: app.presentedSession)?.phase == SessionPhase.Connected }
    }

    @Test fun proxmoxConsoleAndSeveralSessions() {
        launch()
        waitTag("folder-Laboratoire").performClick()
        waitTag("folder-Proxmox").performClick()
        waitTag("entry-vm-101 (console)").performClick()
        waitTag("entryOpen").performClick()
        connected()
        waitText("pve-01 (démo)")
        val console = FixtureHttp.log.last { it.path == "/api/entries/e-console/connect" }
        assertTrue("console native demandée", console.bodyText.contains("\"native\":true"))
        shot("13-console-proxmox")
        waitTag("sessionMinimize").performClick()
        waitTag("sessionsPill")
        waitTag("back").performClick()
        waitTag("entry-pve-02").performClick()
        waitTag("entryOpen").performClick()
        waitTag("sessionSwitcher").performClick()
        waitText("Réduire toutes les sessions")
        shot("14-sessions")
        waitText("Réduire toutes les sessions").performClick()
        waitText("2 sessions ouvertes")
    }

    @Test fun workspaceOpensAllSessions() {
        launch()
        waitTag("tab-MORE").performClick()
        waitTag("moreWorkspaces").performClick()
        waitTag("workspace-Tour du matin")
        shot("15-espaces")
        waitTag("workspace-Tour du matin").performClick()
        waitTag("sessionSwitcher")
        assertEquals(2, app.sessions.size)
        waitTag("sessionMinimize").performClick()
        waitText("2 sessions ouvertes")
    }

    @Test fun sessionFiles() {
        launch()
        waitTag("entry-pve-01").performClick()
        waitTag("entryOpen").performClick()
        connected()
        waitTag("sessionFiles").performClick()
        compose.waitUntil(10_000) { settle(50); compose.onAllNodes(androidx.compose.ui.test.hasTestTag("file-rapport.txt"), true).fetchSemanticsNodes().isNotEmpty() }
        waitTag("file-Documents").performClick()
        compose.waitUntil(10_000) { settle(50); compose.onAllNodes(androidx.compose.ui.test.hasTestTag("file-notes.md"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("16-fichiers")
        waitTag("file-notes.md").performClick()
        compose.waitUntil(10_000) { settle(50); compose.onAllNodes(androidx.compose.ui.test.hasText("Terminé"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("17-fichier-telecharge")
        val transport = app.presentedSession!!.screen.engine!!.let { e ->
            e.javaClass.getDeclaredField("transport").apply { isAccessible = true }.get(e) as ScriptedTransport
        }
        assertTrue(transport.sent.contains("3.get,1.0,19./Documents/notes.md;"))
    }

    @Test fun commandBroadcast() {
        launch()
        waitTag("tab-COMMANDS").performClick()
        waitTag("commandTargets").performClick()
        waitTag("targetFolder-Laboratoire").performClick()
        shot("18-commande-cibles")
        waitTag("back").performClick()
        waitText("2 sélectionné(s)")
        waitTag("commandText").performTextInput("uptime")
        waitTag("commandRun").performClick()
        waitTag("result-pve-01")
        compose.waitUntil(10_000) { settle(100); app.commands.run?.finished == true }
        waitTag("commandSummary").assertTextContains("1 réussie(s)", substring = true)
        shot("19-commande-resultats")
        waitTag("result-pve-01").performClick()
        waitTag("runOutput").assertTextContains("load average", substring = true)
        shot("20-commande-sortie")
    }

    @Test fun editingAndAdministration() {
        launch()
        waitText("Favoris")
        // Nouvelle entrée depuis la racine.
        waitTag("addMenu").performClick()
        waitTag("addEntry").performClick()
        waitTag("editorName").performTextInput("nouveau")
        waitTag("editorHost").performTextInput("192.0.2.50")
        shot("21-nouvelle-entree")
        waitTag("editorSave").performClick()
        compose.waitUntil(5_000) { FixtureHttp.log.any { it.method == "POST" && it.path == "/api/entries" } }
        val created = FixtureHttp.log.last { it.method == "POST" && it.path == "/api/entries" }
        assertTrue(created.bodyText, created.bodyText.contains("\"host\":\"192.0.2.50\"") && created.bodyText.contains("\"port\":22"))
        // Fiche existante : pièces jointes, puis modification (fiche complète relue).
        waitTag("entry-pve-01").performClick()
        waitTag("attachment-schema-reseau.pdf").performScrollTo()
        shot("21b-pieces-jointes")
        waitTag("entryEdit").performClick()
        waitTag("editorName").assertTextContains("pve-01")
        waitText("Réglages avancés conservés", substring = true)
        shot("22-modifier-entree")
        waitTag("sheetCancel").performClick()
        // Dossier : identifiants hérités.
        waitTag("back").performClick()
        waitTag("folder-Windows").performClick()
        waitTag("folderActions").performClick()
        waitTag("editFolder").performClick()
        waitText("Administrateur du domaine")
        shot("22b-dossier")
        waitTag("sheetCancel").performClick()
        // Coffre : nouvel identifiant.
        waitTag("tab-VAULT").performClick()
        waitTag("credNew").performClick()
        waitTag("credName")
        shot("23-nouvel-identifiant")
        waitTag("sheetCancel").performClick()
        // Administration.
        waitTag("tab-MORE").performClick()
        waitTag("moreSources").performClick()
        waitTag("source-Cluster labo").performClick()
        waitTag("sourceSync").performClick()
        waitText("Connexion réussie : Proxmox VE 9.2")
        shot("24-source")
        waitTag("back").performClick()
        waitTag("back").performClick()
        waitTag("moreDuplicates").performClick()
        waitText("ancien-pve-02")
        shot("25-doublons")
        waitTag("back").performClick()
        waitTag("moreImport").performClick()
        waitTag("importPick")
        waitTag("back").performClick()
        waitTag("moreBackup").performClick()
        waitTag("backupPassword").performTextInput("motdepasse-demo")
        waitTag("backupConfirm").performTextInput("motdepasse-demo")
        shot("26-sauvegarde")
    }
}
