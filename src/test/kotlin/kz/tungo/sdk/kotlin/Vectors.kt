package kz.tungo.sdk.kotlin

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The shared conformance vectors (conformance/vectors.json), read from the copy in the repository. */
internal object Vectors {
    val root: JsonObject by lazy {
        val path = System.getProperty("tungo.vectors") ?: "conformance/vectors.json"
        Json.parseToJsonElement(File(path).readText(Charsets.UTF_8)).jsonObject
    }

    fun section(vararg path: String): JsonElement {
        var current: JsonElement = root
        for (key in path) current = current.jsonObject.getValue(key)
        return current
    }

    fun cases(vararg path: String): List<JsonObject> = section(*path).jsonArray.map { it.jsonObject }
}

internal fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

internal fun JsonObject.strOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

internal fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

internal fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.content == "true"

internal fun JsonObject.isNullOrAbsent(name: String): Boolean = this[name] == null || this[name] is JsonNull

internal fun JsonObject.stringMap(name: String): Map<String, String> = obj(name).mapValues { it.value.jsonPrimitive.content }

internal val JsonObject.caseName: String get() = str("name")
