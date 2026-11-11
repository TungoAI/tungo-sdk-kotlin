package kz.tungo.sdk.kotlin.errors

import kz.tungo.sdk.kotlin.ResponseHeaders

/** The client is configured incorrectly, for example the API key is missing. Raised before any request. */
public class TungoConfigException(message: String, cause: Throwable? = null) : TungoException(message, cause)

/** An argument is invalid, for example an empty path parameter or a malformed amount. Raised before any request. */
public class TungoValidationException(message: String, cause: Throwable? = null) : TungoException(message, cause)

/** The request could not be sent or the connection broke before a complete response arrived. */
public class TungoConnectionException(message: String, cause: Throwable? = null) : TungoException(message, cause)

/**
 * A time limit expired: the request timeout, the idle timeout of a stream, or the deadline of a
 * waiting helper (then it is a [TungoWaitTimeoutException]). It is a local condition, not an
 * HTTP 408 response.
 */
public open class TungoTimeoutException(message: String, cause: Throwable? = null) : TungoException(message, cause)

/**
 * The deadline of a waiting helper (`waitForRun`, `runScenarioAndWait`, `waitForBatch`) passed
 * before the work reached a final state. No request timed out: the work may still finish, and
 * waiting again is safe.
 */
public class TungoWaitTimeoutException(message: String, cause: Throwable? = null) : TungoTimeoutException(message, cause)

/** A successful response whose body could not be decoded. Such a response is never retried. */
public class TungoDecodeException internal constructor(
    message: String,
    cause: Throwable?,
    /** HTTP status of the response. */
    public val status: Int,
    /** Headers of the response. */
    public val headers: ResponseHeaders,
    /** The body as received. */
    public val body: String,
) : TungoException(message, cause) {
    /** Request id assigned by the API (header x-request-id). */
    public val requestId: String? get() = headers["x-request-id"]
}

/** Auto-pagination stopped because the API returned a cursor it had already returned on the way. */
public class TungoPaginationException(message: String) : TungoException(message)
