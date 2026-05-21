package com.github.xepozz.ide.introspector.exec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Best-effort conversion of a Kotlin `Any?` result into a JSON-compatible structure.
 *
 * Strategy:
 *  - null            → JsonNull
 *  - primitives/Str  → JsonPrimitive
 *  - Map<*, *>       → JsonObject (string keys, recursively serialised values)
 *  - Iterable/Array  → JsonArray
 *  - everything else → toString() (and emits a warning the caller can surface)
 */
object ResultSerializer {

    data class Result(val json: kotlinx.serialization.json.JsonElement, val warnings: List<String>)

    fun toJson(value: Any?): Result {
        val warnings = mutableListOf<String>()
        val element = convert(value, warnings, depth = 0)
        return Result(element, warnings)
    }

    private fun convert(v: Any?, warnings: MutableList<String>, depth: Int): kotlinx.serialization.json.JsonElement {
        if (depth > 6) {
            warnings.add("Result depth >6 truncated to toString()")
            return JsonPrimitive(v?.toString() ?: "")
        }
        return when (v) {
            null -> JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is Map<*, *> -> {
                val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
                for ((k, vv) in v) {
                    map[k?.toString() ?: "null"] = convert(vv, warnings, depth + 1)
                }
                JsonObject(map)
            }
            is Iterable<*> -> JsonArray(v.map { convert(it, warnings, depth + 1) })
            is Array<*> -> JsonArray(v.map { convert(it, warnings, depth + 1) })
            else -> {
                warnings.add("Result of type ${v.javaClass.name} is not serializable — fallback to toString()")
                JsonPrimitive(v.toString())
            }
        }
    }

    val JSON = Json { encodeDefaults = true; prettyPrint = false; explicitNulls = false }
}
