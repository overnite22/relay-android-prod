package io.github.overnite22.relay

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Version affichée dans Plus → Serveur. */
object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")
}
