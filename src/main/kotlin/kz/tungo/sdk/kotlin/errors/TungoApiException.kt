package kz.tungo.sdk.kotlin.errors

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.json.JsonElement
import kz.tungo.sdk.kotlin.ResponseHeaders
import kz.tungo.sdk.kotlin.models.FieldError

/**
 * The API answered with an error status.
 *
 * The subclass is chosen by the error.type field of the response body and, when the body is not
 * the error envelope of the API, by the HTTP status.
 */
public open class TungoApiException internal constructor(data: ApiErrorData) : TungoException(data.describe()) {
    /** HTTP status code. */
    public val status: Int = data.status

    /** error.type as sent by the API, or the type derived from the status. */
    public val type: String = data.type

    /** error.code: the machine-readable reason, when the API sent one. */
    public val code: String? = data.code

    /** error.message: the human-readable text of the API, when the body is the error envelope. */
    public val apiMessage: String? = data.apiMessage

    /** Request id from the body, else from the x-request-id header. */
    public val requestId: String? = data.requestId

    /** Per-field validation errors; empty when the API sent none. */
    public val fields: List<FieldError> = data.fields

    /** Delay asked by the API in seconds (retry-after-ms, then Retry-After); null when absent. */
    public val retryAfterSeconds: Double? = data.retryAfterSeconds

    /** [retryAfterSeconds] as a duration. */
    public val retryAfter: Duration? get() = retryAfterSeconds?.let { (it * 1e9).toLong().nanoseconds }

    /** Every other property of the error object (resets_at, permission, ...). */
    public val extra: Map<String, JsonElement> = data.extra

    /** True when the body was the error envelope of the API. */
    public val isEnvelope: Boolean = data.envelope

    /** Response headers. */
    public val headers: ResponseHeaders = data.headers

    /** The response body as received. */
    public val body: String = data.body
}

/** error.type invalid_request_error: the request is malformed or not allowed in the current state. */
public class InvalidRequestException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type authentication_error: the API key is missing, wrong or revoked. */
public class AuthenticationException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type payment_required_error: the balance or the plan does not cover the request. */
public class PaymentRequiredException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type permission_error: the key lacks a permission. */
public class PermissionDeniedException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type not_found_error: the resource does not exist. */
public class NotFoundException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type conflict_error: the request conflicts with the current state. */
public class ConflictException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type gone_error: the resource existed but is no longer available. */
public class GoneException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type limit_error: a rate limit or a plan limit was reached. */
public class LimitException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type api_error: the API failed internally. */
public class InternalServerException internal constructor(data: ApiErrorData) : TungoApiException(data)

/** error.type unavailable_error: the API or an upstream service is temporarily unavailable. */
public class ServiceUnavailableException internal constructor(data: ApiErrorData) : TungoApiException(data)

internal class ApiErrorData(
    val status: Int,
    val type: String,
    val kind: String,
    val code: String?,
    val apiMessage: String?,
    val requestId: String?,
    val fields: List<FieldError>,
    val retryAfterSeconds: Double?,
    val extra: Map<String, JsonElement>,
    val envelope: Boolean,
    val headers: ResponseHeaders,
    val body: String,
) {
    fun describe(): String {
        val text = StringBuilder("Tungo API error ").append(status).append(' ').append(type)
        if (code != null) text.append(" (code ").append(code).append(')')
        if (requestId != null) text.append(" [request id ").append(requestId).append(']')
        if (!apiMessage.isNullOrEmpty()) text.append(": ").append(apiMessage)
        return text.toString()
    }

    fun toException(): TungoApiException = when (kind) {
        "invalid_request_error" -> InvalidRequestException(this)
        "authentication_error" -> AuthenticationException(this)
        "payment_required_error" -> PaymentRequiredException(this)
        "permission_error" -> PermissionDeniedException(this)
        "not_found_error" -> NotFoundException(this)
        "conflict_error" -> ConflictException(this)
        "gone_error" -> GoneException(this)
        "limit_error" -> LimitException(this)
        "unavailable_error" -> ServiceUnavailableException(this)
        "api_error" -> InternalServerException(this)
        else -> TungoApiException(this)
    }
}
