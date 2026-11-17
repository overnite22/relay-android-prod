package io.github.overnite22.relay

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.overnite22.relay.sessions.SessionPhase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "fr-rFR-w411dp-h891dp-xxhdpi")
class VaultAndToolsTest : UiTestBase() {
    @Test fun availabilityVaultQuickConnectAndAudit() {
        launch()
        waitText("Favoris")
        waitTag("tab-AVAILABILITY").performClick()
        waitTag("entry-pve-02")
        waitText("Délai dépassé", substring = true)
        shot("05-disponibilite")
        waitTag("availabilityFilter-1").performClick()
        waitTag("entry-pve-01")

        waitTag("tab-VAULT").performClick()
        waitTag("credential-Console fournisseur").performClick()
        waitTag("credentialReveal").performClick()
        waitTag("credentialPassword").assertTextContains("Exemple-Secret-42")
        waitTag("credentialShowTotp").performClick()
        waitTag("credentialTotp").assertTextContains("492 039")
        shot("06-coffre")

        waitTag("tab-MORE").performClick()
        shot("07a-plus")
        waitTag("moreQuick").performClick()
        waitTag("quickHost").performTextInput("192.0.2.30")
        waitTag("quickConnect")
        shot("07-connexion-rapide")
        waitTag("back").performClick()
        waitTag("moreAudit").performClick()
        waitText("Appareil appairé")
        shot("08-journal")
    }

    @Test fun quickConnectOpensSession() {
        launch()
        waitTag("tab-MORE").performClick()
        waitTag("moreQuick").performClick()
        waitTag("quickHost").performTextInput("192.0.2.30")
        waitTag("quickConnect").performClick()
        waitTag("remoteScreen")
        compose.waitUntil(10_000) { settle(50); app.presentedSession?.phase == SessionPhase.Connected }
        val quick = FixtureHttp.log.last { it.path == "/api/quick-connect" }
        assertTrue(quick.bodyText, quick.bodyText.contains("\"host\":\"192.0.2.30\"") && !quick.bodyText.contains("credentialId"))
    }
}
