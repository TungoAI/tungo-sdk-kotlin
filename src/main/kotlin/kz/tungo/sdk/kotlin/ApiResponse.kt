package kz.tungo.sdk.kotlin

/** A decoded response together with its HTTP status and headers. */
public class ApiResponse<out T> internal constructor(
    /** The decoded body. */
    public val body: T,
    /** HTTP status code. */
    public val status: Int,
    /** Response headers. */
    public val headers: ResponseHeaders,
) {
    /** Request id assigned by the API (header x-request-id). */
    public val requestId: String? get() = headers["x-request-id"]

    /** Rate-limit state reported by this response; null when the headers are absent. */
    public val rateLimit: RateLimit? get() = RateLimit.from(headers)

    override fun toString(): String = "ApiResponse(status=$status, requestId=$requestId, body=$body)"
}
