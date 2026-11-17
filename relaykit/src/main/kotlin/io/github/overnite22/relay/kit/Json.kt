package io.github.overnite22.relay.kit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Réglages JSON communs : champs inconnus ignorés (serveur plus récent), valeurs par défaut toujours envoyées. */
object RelayJson {
    /** Les `null` sont envoyés explicitement (folderId, credentialId… attendus par le serveur). */
    val default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        coerceInputValues = true
    }

    /** Les `null` sont omis : secrets inchangés, identifiant facultatif. */
    val omitNulls: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
}

/** Énumération transmise sous forme de chaîne ; une valeur inconnue (serveur plus récent) devient [fallback]. */
interface RawValued {
    val raw: String
}

open class LenientEnumSerializer<T>(
    name: String,
    private val values: Array<T>,
    private val fallback: T,
) : KSerializer<T> where T : Enum<T>, T : RawValued {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.raw)
    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return values.firstOrNull { it.raw == raw } ?: fallback
    }
}
