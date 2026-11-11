package kz.tungo.sdk.kotlin

/**
 * Rate-limit state reported by one response (headers x-ratelimit-limit, x-ratelimit-remaining
 * and x-ratelimit-reset).
 */
public data class RateLimit(
    /** Requests allowed in the current window. */
    public val limit: Long?,
    /** Requests left in the current window. */
    public val remaining: Long?,
    /** Seconds until the window resets. */
    public val reset: Long?,
) {
    internal companion object {
        fun from(headers: ResponseHeaders): RateLimit? {
            val limit = headers["x-ratelimit-limit"]?.trim()?.toLongOrNull()
            val remaining = headers["x-ratelimit-remaining"]?.trim()?.toLongOrNull()
            val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
            if (limit == null && remaining == null && reset == null) return null
            return RateLimit(limit, remaining, reset)
        }
    }
}
