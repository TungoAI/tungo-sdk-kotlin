package kz.tungo.sdk.kotlin.internal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kz.tungo.sdk.kotlin.ResponseHeaders
import kz.tungo.sdk.kotlin.errors.ApiErrorData
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.models.FieldError

/** Builds API exceptions from error responses. */
internal object Errors {
    private val kinds: Set<String> = setOf("invalid_request_error", "authentication_error", "payment_required_error", "permission_error", "not_found_error", "conflict_error", "gone_error", "limit_error", "api_error", "unavailable_error")

    /** The error type implied by a status when the body is not the error envelope. */
    fun statusFallback(status: Int): String = when {
        status == 401 -> "authentication_error"
        status == 402 -> "payment_required_error"
        status == 403 -> "permission_error"
        status == 404 -> "not_found_error"
        status == 409 -> "conflict_error"
        status == 410 -> "gone_error"
        status == 429 -> "limit_error"
        status == 502 || status == 503 || status == 504 -> "unavailable_error"
        status >= 500 -> "api_error"
        else -> "invalid_request_error"
    }

    fun parse(status: Int, headers: ResponseHeaders, body: String, nowMillis: Long): ApiErrorData {
        val fallback = statusFallback(status)
        val headerRequestId = headers["x-request-id"]?.takeIf { it.isNotEmpty() }
        val retryAfter = Retry.retryAfterSeconds(headers, nowMillis)
        val root = parseObject(body)
        val error = root?.get("error") as? JsonObject
        val type = error?.stringOrNull("type")
        val message = error?.stringOrNull("message")
        if (root == null || error == null || type == null || message == null) {
            return ApiErrorData(
                status = status, type = fallback, kind = fallback, code = null, apiMessage = null, requestId = headerRequestId,
                fields = emptyList(), retryAfterSeconds = retryAfter, extra = emptyMap(), envelope = false, headers = headers, body = body,
            )
        }
        val extra = LinkedHashMap<String, JsonElement>()
        for ((key, value) in error) {
            if (key != "type" && key != "message" && key != "code" && key != "fields") extra[key] = value
        }
        val fields = error["fields"]?.decodeOrNull(ListSerializer(FieldError.serializer())) ?: emptyList()
        return ApiErrorData(
            status = status,
            type = type,
            kind = if (type in kinds) type else fallback,
            code = error.stringOrNull("code"),
            apiMessage = message,
            requestId = root.stringOrNull("request_id")?.takeIf { it.isNotEmpty() } ?: headerRequestId,
            fields = fields,
            retryAfterSeconds = retryAfter,
            extra = extra,
            envelope = true,
            headers = headers,
            body = body,
        )
    }

    fun fromResponse(status: Int, headers: ResponseHeaders, body: String, nowMillis: Long): TungoApiException =
        parse(status, headers, body, nowMillis).toException()

    /**
     * The API error carried by an event named "error" of a stream. Its data is either the error
     * envelope of the API ({error: {type, code, message}, request_id}) or the short form of the
     * feed ({error: "<code>", message: "<text>"}); anything else is kept as the message.
     */
    fun fromStreamEvent(status: Int, headers: ResponseHeaders, data: String): TungoApiException =
        fromStreamObject(status, headers, parseObject(data), data)

    fun fromStreamObject(status: Int, headers: ResponseHeaders, root: JsonObject?, data: String): TungoApiException {
        var type: String? = null
        var code: String? = null
        var message: String? = null
        var requestId: String? = null
        val extra = LinkedHashMap<String, JsonElement>()
        val nested = root?.get("error")
        if (root != null && nested is JsonObject) {
            type = nested.stringOrNull("type")
            code = nested.stringOrNull("code")
            message = nested.stringOrNull("message")
            requestId = root.stringOrNull("request_id")
            for ((key, value) in nested) {
                if (key != "type" && key != "message" && key != "code" && key != "fields") extra[key] = value
            }
        } else if (root != null) {
            code = root.stringOrNull("error")
            message = root.stringOrNull("message")
        } else if (data.isNotEmpty()) {
            message = data
        }
        val known = type ?: "api_error"
        return ApiErrorData(
            status = status,
            type = known,
            kind = if (known in kinds) known else "api_error",
            code = code,
            apiMessage = message,
            requestId = requestId?.takeIf { it.isNotEmpty() } ?: headers["x-request-id"]?.takeIf { it.isNotEmpty() },
            fields = emptyList(),
            retryAfterSeconds = null,
            extra = extra,
            envelope = nested is JsonObject && type != null && message != null,
            headers = headers,
            body = data,
        ).toException()
    }

    fun parseObject(body: String): JsonObject? = try {
        TungoJson.decoder.parseToJsonElement(body) as? JsonObject
    } catch (ignored: SerializationException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }
}
