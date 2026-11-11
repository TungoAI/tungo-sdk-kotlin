package kz.tungo.sdk.kotlin.internal

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kz.tungo.sdk.kotlin.ResponseHeaders

/** Retry rules shared by ordinary and streaming requests. */
internal object Retry {
    const val BASE_SECONDS: Double = 0.5
    const val MAX_SECONDS: Double = 8.0
    const val JITTER_MIN: Double = 0.75
    const val MAX_SERVER_DELAY_SECONDS: Double = 60.0

    private val numberPattern = Regex("^[0-9]+(\\.[0-9]+)?$")
    private val dateFormats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")

    /** Statuses worth repeating. */
    fun isRetryableStatus(status: Int): Boolean =
        status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504

    /** A request may be repeated when its method is idempotent or it carries an idempotency key. */
    fun isRepeatable(method: String, hasIdempotencyKey: Boolean): Boolean = when (method.uppercase(Locale.ROOT)) {
        "GET", "HEAD", "PUT", "DELETE" -> true
        else -> hasIdempotencyKey
    }

    /** Delay before retry number [attempt] (0-based): min(0.5 s * 2^attempt, 8 s) * jitter in [0.75, 1]. */
    fun backoffSeconds(attempt: Int, random: Double): Double {
        val exponential = if (attempt >= 5) MAX_SECONDS else minOf(BASE_SECONDS * (1 shl attempt), MAX_SECONDS)
        val jitter = JITTER_MIN + (1.0 - JITTER_MIN) * random.coerceIn(0.0, 1.0)
        return exponential * jitter
    }

    /**
     * Delay asked by the server in seconds: retry-after-ms first, then Retry-After as seconds or
     * as an HTTP date. Null when neither header holds a usable value.
     */
    fun retryAfterSeconds(headers: ResponseHeaders, nowMillis: Long): Double? {
        val millis = headers["retry-after-ms"]?.trim()
        if (millis != null && numberPattern.matches(millis)) return millis.toDouble() / 1000.0
        val value = headers["retry-after"]?.trim() ?: return null
        if (numberPattern.matches(value)) return value.toDouble()
        val date = parseHttpDate(value) ?: return null
        return maxOf(0.0, (date - nowMillis) / 1000.0)
    }

    private fun parseHttpDate(value: String): Long? {
        for (pattern in dateFormats) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            val position = ParsePosition(0)
            val parsed = format.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return null
    }
}
