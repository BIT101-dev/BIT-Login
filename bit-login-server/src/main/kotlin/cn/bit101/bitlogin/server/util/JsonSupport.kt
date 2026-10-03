package cn.bit101.bitlogin.server.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical `Any?` -> [JsonElement] conversion shared by every route, the
 * challenge snapshot serializer, and the registration-token JWT builder.
 *
 * Handles nested maps/lists recursively, passes [JsonElement] through
 * unchanged, preserves JSON value types (string/number/boolean), and maps
 * `null` to [JsonNull]. Previously this logic was duplicated in AuthRoutes,
 * JwbRoutes, AuthServiceExecutor and RegistrationToken with subtly different
 * behaviour (some stringified lists, some dropped them entirely).
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(this.toString())
}
