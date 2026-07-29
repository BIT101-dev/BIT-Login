package cn.bit101.bitlogin.server.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import cn.bit101.bitlogin.http.CookieDetail
import cn.bit101.bitlogin.http.HttpClient

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this == JsonNull) null else content

object SessionSerializer {
    fun serialize(client: HttpClient): JsonObject = buildJsonObject {
        put("cookies", buildJsonArray {
            client.cookieDetails().forEach { c ->
                add(buildJsonObject {
                    put("name", c.name)
                    put("value", c.value)
                    put("domain", c.domain)
                    put("path", c.path)
                    put("secure", c.secure)
                    put("expires", c.expires?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
        put("headers", buildJsonObject {
            client.headers.forEach { (k, v) -> put(k, v) }
        })
        put("trust_env", true)
    }

    suspend fun restore(value: JsonObject): HttpClient {
        val client = HttpClient()
        value["headers"]?.jsonObject?.forEach { (k, v) ->
            client.headers[k] = v.jsonPrimitive.content
        }
        value["cookies"]?.let { it as? JsonArray }?.forEach { item ->
            val cookie = item as? JsonObject ?: return@forEach
            val name = cookie["name"]?.jsonPrimitive?.content ?: return@forEach
            val cValue = cookie["value"]?.jsonPrimitive?.content ?: ""
            val domain = cookie["domain"]?.jsonPrimitive?.content ?: ""
            val path = cookie["path"]?.jsonPrimitive?.content ?: "/"
            val secure = cookie["secure"]?.jsonPrimitive?.content == "true"
            val expires = cookie["expires"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull()
            client.addCookie(name, cValue, domain, path, secure, expires)
        }
        return client
    }
}
