package io.github.overnite22.relay

import android.os.Looper
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "fr-rFR-w411dp-h891dp-xxhdpi")
class RecordingTest : UiTestBase() {
    @Test fun recordingPlayback() {
        launch()
        waitTag("tab-MORE").performClick()
        waitTag("moreRecordings").performClick()
        waitTag("recording-pve-01")
        waitText("Tronqué")
        shot("11-enregistrements")
        waitTag("recording-pve-01").performClick()
        waitTag("playerToggle")
        // Pauses sautées : la fin (60 s) est atteinte en quelques secondes d'horloge simulée.
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            Thread.sleep(5)
            compose.onAllNodesWithContentDescriptionCompat("Lecture")
        }
        waitTag("playerPosition").assertTextEquals("0:59")
        shot("12-lecture")
        waitTag("playerClose").performClick()
        waitTag("recording-pve-01")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionCompat(d: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}
