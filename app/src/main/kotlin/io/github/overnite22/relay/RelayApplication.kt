package io.github.overnite22.relay

import android.app.Application
import io.github.overnite22.relay.core.AppSession
import io.github.overnite22.relay.core.KeystoreAccountStore
import io.github.overnite22.relay.core.Prefs

/** Application : l'état global survit aux recréations d'activité (rotation, retour d'arrière-plan). */
class RelayApplication : Application() {
    /** Remplacé par les tests (faux serveur, flux Guacamole scripté) avant le lancement de l'activité. */
    lateinit var session: AppSession

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        session = AppSession(this, KeystoreAccountStore(this), prefs)
    }
}
