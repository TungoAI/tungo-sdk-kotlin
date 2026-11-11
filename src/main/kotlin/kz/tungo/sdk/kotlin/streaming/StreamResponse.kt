package kz.tungo.sdk.kotlin.streaming

import kotlinx.coroutines.flow.Flow
import kz.tungo.sdk.kotlin.RateLimit
import kz.tungo.sdk.kotlin.ResponseHeaders

/**
 * An open event stream: the response status and headers, and the events.
 *
 * It is valid only inside the block it is passed to; the connection is closed when the block
 * returns or is cancelled.
 */
public class StreamResponse<out T> internal constructor(
    /** HTTP status code. */
    public val status: Int,
    /** Response headers. */
    public val headers: ResponseHeaders,
    /**
     * The typed events, delivered as they arrive. The flow can be collected once. Keep-alive
     * events without data are skipped.
     */
    public val events: Flow<T>,
) {
    /** Request id assigned by the API (header x-request-id). */
    public val requestId: String? get() = headers["x-request-id"]

    /** Rate-limit state reported by this response; null when the headers are absent. */
    public val rateLimit: RateLimit? get() = RateLimit.from(headers)
}
