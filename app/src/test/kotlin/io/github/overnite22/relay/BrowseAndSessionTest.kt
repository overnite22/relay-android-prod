package io.github.overnite22.relay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.overnite22.relay.kit.guac.ScriptedTransport
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
class BrowseAndSessionTest : UiTestBase() {
    @Test fun pairingScreen() {
        launch(paired = false)
        waitText("Relay")
        waitTag("pairManual").performClick()
        waitTag("pairServer").performTextInput("http://relay.example.com")
        waitTag("pairToken").performTextInput("rlt_" + "y".repeat(43))
        waitTag("pairConnect").performClick()
        waitText("HTTPS", substring = true)
        shot("01-appairage")
    }

    @Test fun manualPairingSucceeds() {
        launch(paired = false)
        waitTag("pairManual").performClick()
        waitTag("pairServer").performTextInput("relay.example.com")
        waitTag("pairToken").performTextInput("rlt_" + "y".repeat(43))
        waitTag("pairConnect").performClick()
        waitText("Favoris")
        assertEquals("https://relay.example.com", app.account?.server)
    }

    @Test fun browseFoldersAndEntry() {
        launch()
        waitText("Favoris")
        shot("02-connexions")
        waitTag("folder-Laboratoire").performClick()
        waitTag("folder-Proxmox").performClick()
        waitTag("entry-pve-02")
        shot("03-dossier")
        waitTag("entry-pve-01").performClick()
        waitTag("entryOpen")
        waitTag("entryTest").performClick()
        waitText("Port accessible")
        waitText("Maintenance", substring = true)
        shot("04-fiche")
        waitTag("back").performClick()
        waitTag("entry-pve-02")
    }

    @Test fun searchFindsEntries() {
        launch()
        waitTag("search").performTextInput("192.0.2.2")
        waitText("2 résultats")
        waitTag("entry-srv-ad-01").assertIsDisplayed()
    }

    @Test fun scriptedSessionRendersAndTypes() {
        launch()
        waitTag("folder-Windows").performClick()
        waitTag("entry-srv-ad-01").performClick()
        waitTag("entryOpen").performClick()
        waitTag("remoteScreen")
        compose.waitUntil(10_000) { settle(50); app.presentedSession?.phase == SessionPhase.Connected }
        waitText("pve-01 (démo)")
        settle()
        shot("09-session")
        val controller = app.presentedSession!!
        controller.insertText("ls\n")
        controller.ctrl = true
        controller.insertText("c")
        settle()
        val transport = transportOf(controller)
        assertTrue("frappe envoyée", transport.sent.any { it == "3.key,3.108,1.1;" })
        assertTrue("Ctrl+C envoyé", transport.sent.containsAll(listOf("3.key,5.65507,1.1;", "3.key,2.99,1.1;", "3.key,5.65507,1.0;")))
        assertTrue("trame acquittée", transport.sent.any { it.startsWith("4.sync,1.1;") })
        waitTag("sessionMinimize").performClick()
        waitTag("sessionsPill")
        shot("10-session-reduite")
        waitTag("sessionsPill").performClick()
        waitTag("remoteScreen")
        waitTag("sessionClose").performClick()
        compose.waitUntil(5_000) { app.sessions.isEmpty() }
    }

    private fun transportOf(c: io.github.overnite22.relay.sessions.SessionController): ScriptedTransport {
        val engine = c.screen.engine!!
        val f = engine.javaClass.getDeclaredField("transport").apply { isAccessible = true }
        return f.get(engine) as ScriptedTransport
    }
}
