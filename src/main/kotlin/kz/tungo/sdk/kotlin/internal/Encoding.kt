package kz.tungo.sdk.kotlin.internal

import kz.tungo.sdk.kotlin.errors.TungoValidationException

private const val HEX = "0123456789ABCDEF"

/** Percent-encodes everything except the RFC 3986 unreserved characters. */
internal fun percentEncode(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size + 8)
    for (b in bytes) {
        val c = b.toInt() and 0xff
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (unreserved) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
        }
    }
    return out.toString()
}

/**
 * Encodes a path parameter as one path segment.
 *
 * @throws TungoValidationException for an empty value, "." and "..", which would change the path
 */
internal fun pathSegment(name: String, value: String): String {
    if (value.isEmpty()) throw TungoValidationException("Path parameter '$name' must not be empty")
    if (value == "." || value == "..") throw TungoValidationException("Path parameter '$name' must not be '.' or '..'")
    return percentEncode(value)
}

/** Builds a query string from typed values; null values are omitted. */
internal class QueryBuilder {
    private val text = StringBuilder()

    private fun append(name: String, value: String) {
        if (text.isNotEmpty()) text.append('&')
        text.append(percentEncode(name)).append('=').append(percentEncode(value))
    }

    fun add(name: String, value: String?): QueryBuilder {
        if (value != null) append(name, value)
        return this
    }

    fun add(name: String, value: Boolean?): QueryBuilder {
        if (value != null) append(name, if (value) "true" else "false")
        return this
    }

    fun add(name: String, value: Int?): QueryBuilder {
        if (value != null) append(name, value.toString())
        return this
    }

    fun add(name: String, value: Long?): QueryBuilder {
        if (value != null) append(name, value.toString())
        return this
    }

    fun add(name: String, value: Double?): QueryBuilder {
        if (value != null) {
            if (value.isNaN() || value.isInfinite()) throw TungoValidationException("Query parameter '$name' must be a finite number")
            val whole = value.toLong()
            append(name, if (whole.toDouble() == value) whole.toString() else value.toString())
        }
        return this
    }

    fun addAll(name: String, values: Iterable<String?>?): QueryBuilder {
        if (values != null) for (v in values) add(name, v)
        return this
    }

    /** The query string without the leading question mark; empty when there are no parameters. */
    fun build(): String = text.toString()
}
