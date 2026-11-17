package io.github.overnite22.relay.core

import android.content.Context

/** Préférences de l'app (récents, verrouillage, affichage des sessions). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var recentIds: List<String>
        get() = p.getString("recentEntryIds", "")!!.split("\n").filter { it.isNotEmpty() }
        set(v) = p.edit().putString("recentEntryIds", v.joinToString("\n")).apply()

    /** Coffre protégé par empreinte, visage ou code de l'appareil. */
    var biometrics: Boolean
        get() = p.getBoolean("biometrics", true)
        set(v) = p.edit().putBoolean("biometrics", v).apply()

    /** Multiplicateur de densité du texte SSH (plus petit = plus de colonnes). */
    var sshText: Float
        get() = p.getFloat("sshTextSize", 0.85f)
        set(v) = p.edit().putFloat("sshTextSize", v).apply()

    /** Pixels distants par dp d'écran pour RDP/VNC. */
    var rdpResolution: Float
        get() = p.getFloat("rdpResolution", 1.5f)
        set(v) = p.edit().putFloat("rdpResolution", v).apply()

    /** Sessions gardées connectées en arrière-plan (notification). */
    var keepAlive: Boolean
        get() = p.getBoolean("keepAlive", true)
        set(v) = p.edit().putBoolean("keepAlive", v).apply()
}
