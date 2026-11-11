package kz.tungo.sdk.kotlin.internal

import kz.tungo.sdk.kotlin.streaming.ServerSentEvent

/**
 * Incremental Server-Sent Events parser (WHATWG HTML, "interpreting an event stream").
 *
 * Bytes are fed in arbitrary chunks. Lines end with LF, CRLF or a lone CR; a UTF-8 sequence or a
 * CRLF pair may be split between chunks; a leading byte order mark is skipped. An event is
 * dispatched at each blank line when its block had at least one data line.
 */
internal class SseParser {
    private var buffer = ByteArray(4096)
    private var length = 0
    private var scanned = 0
    private var started = false

    private val data = StringBuilder()
    private var hasData = false
    private var event = ""
    private var lastId: String? = null
    private var retry: Long? = null

    /** Consumes a chunk and appends every event completed by it to [out]. */
    fun feed(chunk: ByteArray, offset: Int, count: Int, out: MutableList<ServerSentEvent>) {
        ensureCapacity(length + count)
        System.arraycopy(chunk, offset, buffer, length, count)
        length += count
        if (!started) {
            if (length < 3 && isBomPrefix()) return
            if (length >= 3 && isBomPrefix()) {
                System.arraycopy(buffer, 3, buffer, 0, length - 3)
                length -= 3
            }
            started = true
        }
        var lineStart = 0
        var i = scanned
        while (i < length) {
            val b = buffer[i]
            if (b != CR && b != LF) {
                i++
                continue
            }
            var next = i + 1
            if (b == CR) {
                if (next == length) break // the CR may be the first half of a CRLF pair
                if (buffer[next] == LF) next++
            }
            line(String(buffer, lineStart, i - lineStart, Charsets.UTF_8), out)
            lineStart = next
            i = next
        }
        if (lineStart > 0) {
            System.arraycopy(buffer, lineStart, buffer, 0, length - lineStart)
            length -= lineStart
            i -= lineStart
        }
        scanned = i
    }

    /** Signals the end of the stream: a trailing CR ends its line, an unfinished event is dropped. */
    fun end(out: MutableList<ServerSentEvent>) {
        if (length > 0 && buffer[length - 1] == CR) {
            line(String(buffer, 0, length - 1, Charsets.UTF_8), out)
        }
        length = 0
        scanned = 0
    }

    private fun isBomPrefix(): Boolean {
        for (i in 0 until minOf(length, 3)) if (buffer[i] != BOM[i]) return false
        return true
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var size = buffer.size
        while (size < needed) size = if (size > Int.MAX_VALUE / 2) Int.MAX_VALUE - 8 else size * 2
        buffer = buffer.copyOf(size)
    }

    private fun line(line: String, out: MutableList<ServerSentEvent>) {
        if (line.isEmpty()) {
            if (hasData) {
                out.add(ServerSentEvent(event = event.ifEmpty { "message" }, data = data.toString(), id = lastId, retry = retry))
            }
            data.setLength(0)
            hasData = false
            event = ""
            retry = null
            return
        }
        if (line[0] == ':') return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if (value.indexOf('\u0000') < 0) lastId = value
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retry = value.toLongOrNull() ?: retry
        }
    }

    private companion object {
        const val CR: Byte = 13
        const val LF: Byte = 10
        val BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
