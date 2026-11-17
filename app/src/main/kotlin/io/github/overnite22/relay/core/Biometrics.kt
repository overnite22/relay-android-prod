package io.github.overnite22.relay.core

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Empreinte, visage ou code de l'appareil avant d'afficher un secret du coffre. */
object Biometrics {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private suspend fun authenticate(activity: FragmentActivity, reason: String): Boolean {
        // Aucun verrouillage configuré sur l'appareil : rien à demander (comme l'app iPhone).
        if (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) return true
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Relay")
                    .setSubtitle(reason)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    /** Demande l'authentification si nécessaire ; mémorise le déverrouillage 60 secondes. */
    suspend fun unlock(app: AppSession, context: Context, reason: String = "Afficher un secret du coffre Relay"): Boolean {
        if (!app.prefs.biometrics || app.secretsUnlocked) return true
        val activity = context.findActivity() ?: return false
        val ok = authenticate(activity, reason)
        if (ok) app.markUnlocked()
        return ok
    }

    private fun Context.findActivity(): FragmentActivity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }
}
