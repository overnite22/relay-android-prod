package io.github.overnite22.relay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.overnite22.relay.ui.RelayRoot
import io.github.overnite22.relay.ui.RelayTheme

/** Activité unique ; FragmentActivity pour l'invite biométrique. */
class MainActivity : FragmentActivity() {
    /** Code d'appairage reçu par lien relay://pair (QR lu par l'appareil photo du téléphone). */
    private val pendingPairCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Contenu masqué dans l'écran des apps récentes (secrets, inventaire), captures d'écran autorisées.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val app = (application as RelayApplication).session
        app.restore()
        handle(intent)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (app.phase == io.github.overnite22.relay.core.AppSession.Phase.PAIRED) app.refreshInBackground()
            }

            override fun onStop(owner: LifecycleOwner) = app.lockSecrets()
        })
        setContent {
            RelayTheme {
                RelayRoot(app, pendingPairCode)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "relay") pendingPairCode.value = data.toString()
    }
}
