package kz.tungo.sdk.kotlin.internal

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON configurations of the SDK. */
internal object TungoJson {
    /** Responses: unknown properties are ignored, a missing nullable property is null. */
    val decoder: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Requests: optional properties left at their default are omitted, required nullable
     * properties are written as null.
     */
    val encoder: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = true
    }
}

internal fun Decoder.asJsonDecoder(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("Tungo models can be decoded only from JSON")

internal fun Encoder.asJsonEncoder(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("Tungo models can be encoded only to JSON")

/** Decodes the element, or returns null when it does not have the shape of [T]. */
internal fun <T> JsonElement.decodeOrNull(deserializer: DeserializationStrategy<T>): T? = try {
    TungoJson.decoder.decodeFromJsonElement(deserializer, this)
} catch (ignored: SerializationException) {
    null
} catch (ignored: IllegalArgumentException) {
    null
}

/** True when the object has a primitive property [name] whose text is [value]. */
internal fun JsonObject.hasConst(name: String, value: String): Boolean = (this[name] as? JsonPrimitive)?.content == value

/** The string value of a property, or null when it is absent or not a string. */
internal fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
