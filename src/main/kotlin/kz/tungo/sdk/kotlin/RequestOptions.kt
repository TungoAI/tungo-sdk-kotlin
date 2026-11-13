package kz.tungo.sdk.kotlin

import kotlin.time.Duration
import kz.tungo.sdk.kotlin.errors.TungoConfigException

/**
 * Options of a single request. Every SDK method accepts them as its last parameter:
 *
 *     client.companies.list(requestOptions = RequestOptions { timeout = 5.seconds; maxRetries = 0 })
 *
 * A request is cancelled by cancelling the coroutine that runs it.
 */
public class RequestOptions private constructor(builder: Builder) {
    /** Total timeout of one attempt of this request; null keeps the timeout of the client. */
    public val timeout: Duration? = builder.timeout

    /** Maximum number of retries of this request; null keeps the setting of the client. */
    public val maxRetries: Int? = builder.maxRetries

    /** Idempotency key to send; null lets the SDK generate one where the operation supports it. */
    public val idempotencyKey: String? = builder.idempotencyKey

    /** Idle timeout of an event stream; null keeps the setting of the client. */
    public val streamIdleTimeout: Duration? = builder.streamIdleTimeout

    /** Extra headers of this request. They override the default headers of the client. */
    public val headers: Map<String, String> = builder.headers.toMap()

    init {
        if (timeout != null && !timeout.isPositive()) throw TungoConfigException("timeout must be positive")
        if (streamIdleTimeout != null && !streamIdleTimeout.isPositive()) {
            throw TungoConfigException("streamIdleTimeout must be positive")
        }
        if (maxRetries != null && maxRetries < 0) throw TungoConfigException("maxRetries must not be negative")
        if (idempotencyKey != null) TungoClientOptions.checkHeader("Idempotency-Key", idempotencyKey)
        if (idempotencyKey != null && idempotencyKey.isEmpty()) throw TungoConfigException("idempotencyKey must not be empty")
        for ((name, value) in headers) TungoClientOptions.checkHeader(name, value)
    }

    /** A copy of these options with the given idempotency key. */
    internal fun withIdempotencyKey(key: String): RequestOptions {
        val builder = Builder()
        builder.timeout = timeout
        builder.maxRetries = maxRetries
        builder.idempotencyKey = key
        builder.streamIdleTimeout = streamIdleTimeout
        builder.headers.putAll(headers)
        return builder.build()
    }

    /** A copy of these options with one more header. */
    internal fun withHeader(name: String, value: String): RequestOptions {
        val builder = Builder()
        builder.timeout = timeout
        builder.maxRetries = maxRetries
        builder.idempotencyKey = idempotencyKey
        builder.streamIdleTimeout = streamIdleTimeout
        builder.headers.putAll(headers.filterKeys { !it.equals(name, ignoreCase = true) })
        builder.headers[name] = value
        return builder.build()
    }

    override fun toString(): String =
        "RequestOptions(timeout=$timeout, maxRetries=$maxRetries, idempotencyKey=$idempotencyKey, headers=${headers.keys})"

    /** Mutable builder of [RequestOptions]. */
    @TungoDsl
    public class Builder internal constructor() {
        /** Total timeout of one attempt, including reading the response body. */
        public var timeout: Duration? = null

        /** Maximum number of retries; 0 disables retrying. */
        public var maxRetries: Int? = null

        /**
         * Idempotency key of an operation that declares the `Idempotency-Key` header, instead of
         * the generated one. The same key is sent with every attempt. Any other operation sends no
         * key, not even this one, and a POST or PATCH among them is never retried.
         */
        public var idempotencyKey: String? = null

        /** Idle timeout of an event stream. */
        public var streamIdleTimeout: Duration? = null

        internal val headers: MutableMap<String, String> = LinkedHashMap()

        /** Adds a header to the request. */
        public fun header(name: String, value: String) {
            headers[name] = value
        }

        internal fun build(): RequestOptions = RequestOptions(this)
    }

    public companion object {
        /** Options that change nothing. */
        @JvmField
        public val Default: RequestOptions = Builder().build()
    }
}

/** Builds [RequestOptions]. */
public fun RequestOptions(block: RequestOptions.Builder.() -> Unit): RequestOptions =
    RequestOptions.Builder().apply(block).build()
