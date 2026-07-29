package cn.bit101.bitlogin.util

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun urlEncodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

actual fun decodeBytes(bytes: ByteArray, charsetName: String?): String {
    val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset)
}

actual fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

actual fun base64Decode(str: String): ByteArray = Base64.getDecoder().decode(str)

actual fun uriResolve(base: String, reference: String): String =
    URI(base).resolve(reference).toString()

actual fun uriHost(url: String): String? = URI(url).host

actual fun uriPath(url: String): String = URI(url).path ?: ""

actual fun uriQuery(url: String): String? = URI(url).query

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual val ioDispatcher: CoroutineContext = Dispatchers.IO

actual fun describeNetworkFailure(e: Throwable): String? = when (e) {
    is java.net.ConnectException -> "无法连接统一身份认证服务，请稍后重试"
    is java.net.SocketTimeoutException -> "统一身份认证请求超时，请稍后重试"
    is io.ktor.client.network.sockets.ConnectTimeoutException -> "统一身份认证请求超时，请稍后重试"
    is io.ktor.client.plugins.HttpRequestTimeoutException -> "统一身份认证请求超时，请稍后重试"
    is IOException -> "统一身份认证网络请求失败，请稍后重试"
    else -> null
}

actual typealias Closeable = java.io.Closeable

private val sharedConnectionPool = okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES)

// Each Ktor engine still gets its own OkHttpClient (with its own Dispatcher,
// which Ktor shuts down on close), but all of them share this process-wide
// connection pool so TCP+TLS handshakes to upstream hosts are reused across
// HttpClient instances. evictAll() on engine close only prunes idle
// connections and does not break other engines.
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): KtorClient =
    KtorClient(OkHttp) {
        engine {
            config {
                connectionPool(sharedConnectionPool)
            }
        }
        block()
    }
