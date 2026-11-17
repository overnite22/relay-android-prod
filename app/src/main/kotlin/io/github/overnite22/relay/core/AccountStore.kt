package io.github.overnite22.relay.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.overnite22.relay.kit.RelayJson
import io.github.overnite22.relay.kit.ServerAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage du compte appairé (serveur et jeton d'appareil). */
interface AccountStore {
    fun load(): ServerAccount?
    fun save(account: ServerAccount)
    fun delete()
}

/** Compte chiffré en AES-GCM par une clé du Keystore Android (non exportable, propre à ce téléphone). */
class KeystoreAccountStore(context: Context) : AccountStore {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun load(): ServerAccount? {
        val blob = prefs.getString(FIELD, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            val json = cipher.doFinal(raw, 12, raw.size - 12).toString(Charsets.UTF_8)
            RelayJson.default.decodeFromString(ServerAccount.serializer(), json)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(account: ServerAccount) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(RelayJson.default.encodeToString(ServerAccount.serializer(), account).toByteArray())
        prefs.edit().putString(FIELD, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun delete() {
        prefs.edit().remove(FIELD).apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "relay-account"
        const val FIELD = "sealed"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** Stockage en mémoire (tests). */
class MemoryAccountStore(private var account: ServerAccount? = null) : AccountStore {
    override fun load() = account
    override fun save(account: ServerAccount) {
        this.account = account
    }
    override fun delete() {
        account = null
    }
}
