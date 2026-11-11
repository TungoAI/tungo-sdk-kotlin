package kz.tungo.sdk.kotlin

import java.util.Locale

/** Response headers with case-insensitive names. */
public class ResponseHeaders internal constructor(private val entries: List<Pair<String, String>>) {
    /** Returns the last value of the header, or null when it is absent. */
    public operator fun get(name: String): String? = entries.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Returns every value of the header in the order received. */
    public fun values(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /** Lower-case names of all headers. */
    public val names: Set<String> get() = entries.mapTo(LinkedHashSet()) { it.first.lowercase(Locale.ROOT) }

    /** Returns the headers as a map from lower-case name to values. */
    public fun toMap(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((name, value) in entries) out.getOrPut(name.lowercase(Locale.ROOT)) { ArrayList() }.add(value)
        return out
    }

    override fun toString(): String = toMap().toString()

    internal companion object {
        val Empty: ResponseHeaders = ResponseHeaders(emptyList())

        fun of(map: Map<String, String>): ResponseHeaders = ResponseHeaders(map.map { it.key to it.value })
    }
}
