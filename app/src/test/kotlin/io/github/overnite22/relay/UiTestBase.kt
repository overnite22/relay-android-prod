package io.github.overnite22.relay

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.overnite22.relay.core.AppSession
import org.junit.After
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/** Base des parcours d'écrans : faux serveur, activité réelle, captures dans app/screenshots/. */
abstract class UiTestBase {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()
    protected lateinit var app: AppSession
    private var scenario: ActivityScenario<MainActivity>? = null

    protected fun launch(paired: Boolean = true) {
        val application = ApplicationProvider.getApplicationContext<RelayApplication>()
        app = Fixtures.session(application, paired)
        application.session = app
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After fun tearDown() {
        if (::app.isInitialized) for (s in app.sessions.toList()) app.closeSession(s)
        scenario?.close()
    }

    /** Capture d'écran nommée comme sur l'iPhone. */
    protected fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/$name.png")
    }

    protected fun waitTag(tag: String, timeout: Long = 10_000): SemanticsNodeInteraction {
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)[0]
    }

    protected fun waitText(text: String, substring: Boolean = false, timeout: Long = 10_000): SemanticsNodeInteraction {
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasText(text, substring = substring, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onAllNodes(hasText(text, substring = substring, ignoreCase = true), useUnmergedTree = true)[0]
    }

    protected fun gone(tag: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** Laisse tourner les fils d'arrière-plan (moteur Guacamole) puis la boucle principale. */
    protected fun settle(ms: Long = 300) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }
}
