package cn.bit101.bitlogin.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical recursive conversion of arbitrary values to JSON.
 *
 * Shared by the SSO risk/fingerprint payloads ([BitSsoClient]) and the
 * jxzxehall DTOs ([Credit]); previously each had its own private copy.
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

/** Convenience for map-shaped payloads that are known to produce a JSON object. */
internal fun Map<String, Any?>.toJsonObject(): JsonObject = toJsonElement() as JsonObject
