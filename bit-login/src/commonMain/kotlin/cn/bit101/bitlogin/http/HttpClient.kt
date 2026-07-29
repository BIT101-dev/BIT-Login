package cn.bit101.bitlogin.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse as KtorHttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import cn.bit101.bitlogin.util.Closeable
import cn.bit101.bitlogin.util.createPlatformHttpClient
import cn.bit101.bitlogin.util.decodeBytes
import cn.bit101.bitlogin.util.urlEncodeQuery

/**
 * Thin wrapper over Ktor Client that mirrors Python `requests.Session` semantics:
 * - persistent cookies via [TrackingCookieStorage]
 * - mutable default headers ([headers])
 * - per-call header overrides
 * - per-call redirect control via [allowRedirects]
 * - a [postInterceptor] hook (used by CxcyLogin to inject anti-forgery tokens)
 */
class HttpClient(
    defaultHeaders: Map<String, String> = emptyMap(),
    private val defaultFollowRedirects: Boolean = true,
    private val connectTimeoutMs: Long = 5_000L,
    private val socketTimeoutMs: Long = 25_000L,
) : Closeable {

    /** Mutable map of headers applied to every request. Per-call headers override these. */
    val headers: MutableMap<String, String> = LinkedHashMap(defaultHeaders)

    /** Optional hook invoked before each POST (used by CxcyLogin). */
    var postInterceptor: PostInterceptor? = null

    /** Shared between both redirect-mode clients; exposes [cookieMap] for Python-style access. */
    val cookieStorage = TrackingCookieStorage()

    private val followClient: KtorClient = buildClient(follow = true)
    private val noFollowClient: KtorClient = buildClient(follow = false)

    private fun buildClient(follow: Boolean): KtorClient = createPlatformHttpClient {
        install(HttpCookies) { storage = this@HttpClient.cookieStorage }
        // Ktor's HttpClientConfig.followRedirects defaults to true; when true,
        // Ktor auto-installs HttpRedirect regardless of whether we install it.
        // Setting this flag is the *only* way to actually disable redirect
        // following. The previous `if (follow) install(HttpRedirect)` was a
        // no-op for the no-follow path — Ktor followed redirects anyway,
        // silently breaking every allowRedirects=false caller (SSO login,
        // JWB redirect chain, etc.). Mirrors Python requests' allow_redirects.
        followRedirects = follow
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
        // Handle gzip/deflate so compressed responses from the CAS server are
        // transparently decompressed. Python requests does this automatically.
        install(ContentEncoding) {
            gzip()
            deflate()
            identity()
        }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean? = null,
    ): HttpResponse = doRequest(allowRedirects) { client ->
        client.get(url) { applyHeaders(headers) }.toHttpResponse()
    }

    suspend fun request(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: JsonElement? = null,
        rawBody: String? = null,
        allowRedirects: Boolean? = null,
    ): HttpResponse {
        val requestedUrl = if (query.isEmpty()) url else url + (if ('?' in url) '&' else '?') +
            query.entries.joinToString("&") { "${it.key}=${urlEncodeQuery(it.value)}" }
        if (method == HttpMethod.Get) return get(requestedUrl, headers, allowRedirects)
        if (method == HttpMethod.Post && rawBody == null) return post(requestedUrl, headers, data, json, allowRedirects)
        return doRequest(allowRedirects) { client ->
            client.request(requestedUrl) {
                this.method = method
                applyHeaders(headers)
                rawBody?.let { raw ->
                    headers["Content-Type"]?.let { ct -> contentType(ContentType.parse(ct)) }
                    setBody(raw)
                }
            }.toHttpResponse()
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: JsonElement? = null,
        allowRedirects: Boolean? = null,
    ): HttpResponse {
        val ctx = PostContext(
            url = url,
            headers = LinkedHashMap(headers),
            formData = data?.let { LinkedHashMap(it) },
            json = json,
        )
        postInterceptor?.intercept(ctx)

        return doRequest(allowRedirects) { client ->
            when {
                ctx.formData != null -> client.submitForm(
                    url = url,
                    formParameters = Parameters.build {
                        ctx.formData!!.forEach { (k, v) -> append(k, v) }
                    },
                ) {
                    applyHeaders(ctx.headers)
                }.toHttpResponse()

                ctx.json != null -> client.post(url) {
                    applyHeaders(ctx.headers)
                    contentType(ContentType.parse("application/json; charset=UTF-8"))
                    setBody(ctx.json.toString())
                }.toHttpResponse()

                else -> client.post(url) {
                    applyHeaders(ctx.headers)
                }.toHttpResponse()
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(perCall: Map<String, String>) {
        // Defaults first.
        this@HttpClient.headers.forEach { (k, v) -> header(k, v) }
        // Per-call overrides; remove existing then append.
        perCall.forEach { (k, v) ->
            headers.remove(k)
            headers.append(k, v)
        }
    }

    private suspend fun <T> doRequest(allowRedirects: Boolean?, block: suspend (KtorClient) -> T): T {
        val effectiveFollow = allowRedirects ?: defaultFollowRedirects
        val client = if (effectiveFollow) followClient else noFollowClient
        return block(client)
    }

    fun cookieMap(): Map<String, String> = cookieStorage.asMap()

    fun cookieValue(name: String): String? = cookieStorage.asMap()[name]

    fun cookieString(): String =
        cookieMap().entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun cookieDetails(): List<CookieDetail> = cookieStorage.snapshot().map {
        CookieDetail(it.name, it.value, it.domain ?: "", it.path ?: "/", it.secure, it.expires?.timestamp?.div(1000))
    }

    /** Add a cookie for the given domain. Matches Python `session.cookies.set(name, value, domain=...)`. */
    suspend fun addCookie(
        name: String, value: String,
        domain: String,
        path: String = "/",
        secure: Boolean = false,
        expiresEpochSeconds: Long? = null,
    ) {
        val url = if (secure) Url("https://$domain/") else Url("http://$domain/")
        val expires = expiresEpochSeconds?.let { io.ktor.util.date.GMTDate(it * 1000) }
        cookieStorage.addCookie(
            url,
            Cookie(name = name, value = value, domain = domain, path = path, secure = secure, expires = expires),
        )
    }

    /** Fetch a URL and return raw bytes (for captcha images, etc.). */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean? = null,
    ): ByteArray = doRequest(allowRedirects) { client ->
        client.get(url) { applyHeaders(headers) }.body()
    }

    suspend fun requestBytes(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: JsonElement? = null,
        rawBody: String? = null,
        allowRedirects: Boolean? = null,
    ): ByteArray = request(method, url, headers, query, data, json, rawBody, allowRedirects).bodyBytes
        ?: throw IllegalStateException("HTTP response body bytes are unavailable")

    override fun close() {
        followClient.close()
        noFollowClient.close()
    }
}

class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val bodyText: String,
    val url: String,
    val bodyBytes: ByteArray? = null,
) {
    fun location(): String? = headers["Location"] ?: headers["location"]
    fun isRedirect(): Boolean = status in 300..399
}

class PostContext(
    val url: String,
    val headers: MutableMap<String, String>,
    var formData: MutableMap<String, String>?,
    var json: JsonElement?,
)

data class CookieDetail(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val expires: Long?,
)

fun interface PostInterceptor {
    suspend fun intercept(ctx: PostContext)
}

private suspend fun KtorHttpResponse.toHttpResponse(): HttpResponse {
    val headerMap = LinkedHashMap<String, String>()
    headers.entries().forEach { (k, vs) -> headerMap[k] = vs.firstOrNull() ?: "" }
    val charsetName = headers[HttpHeaders.ContentType]
        ?.split(';')
        ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
    // String replaces malformed input, so a bad upstream page cannot abort authentication.
    val bytes = body<ByteArray>()
    val text = decodeBytes(bytes, charsetName)
    val finalUrl = call.request.url.toString()
    return HttpResponse(status.value, headerMap, text, finalUrl, bytes)
}
