package kz.tungo.sdk.kotlin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import kz.tungo.sdk.kotlin.internal.asJsonDecoder
import kz.tungo.sdk.kotlin.internal.asJsonEncoder

/**
 * A request field that can be left out, sent as JSON null, or sent with a value.
 *
 * Fields the API treats differently when they are missing and when they are null (PATCH
 * semantics) have this type:
 *
 *     WebhookEndpointPatch(channels = NullableField.Null)          // clear the list
 *     WebhookEndpointPatch(channels = NullableField.of(listOf()))  // send an empty list
 *     WebhookEndpointPatch()                                        // leave it unchanged
 */
@Serializable(with = NullableFieldSerializer::class)
public sealed interface NullableField<out T> {
    /** The field is not sent. */
    public data object Absent : NullableField<Nothing>

    /** The field is sent as JSON null. */
    public data object Null : NullableField<Nothing>

    /** The field is sent with [value]. */
    public data class Value<out T>(public val value: T) : NullableField<T>

    /** The value, or null when the field is absent or null. */
    public fun valueOrNull(): T? = (this as? Value<T>)?.value

    public companion object {
        /** Wraps a value; null becomes [Null]. */
        public fun <T : Any> of(value: T?): NullableField<T> = if (value == null) Null else Value(value)
    }
}

internal class NullableFieldSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<NullableField<T>> {
    override val descriptor: SerialDescriptor = valueSerializer.descriptor

    override fun deserialize(decoder: Decoder): NullableField<T> {
        val input = decoder.asJsonDecoder()
        val element = input.decodeJsonElement()
        if (element is JsonNull) return NullableField.Null
        return NullableField.Value(input.json.decodeFromJsonElement(valueSerializer, element))
    }

    override fun serialize(encoder: Encoder, value: NullableField<T>) {
        val output = encoder.asJsonEncoder()
        when (value) {
            is NullableField.Absent -> throw SerializationException("An absent field cannot be encoded outside of an object")
            is NullableField.Null -> output.encodeJsonElement(JsonNull)
            is NullableField.Value -> output.encodeSerializableValue(valueSerializer, value.value)
        }
    }
}
