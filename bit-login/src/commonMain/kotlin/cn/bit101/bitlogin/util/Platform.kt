package cn.bit101.bitlogin.util

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.HttpClientConfig
import kotlin.coroutines.CoroutineContext

// ---- time ----
expect fun currentTimeMillis(): Long

// ---- encoding ----
expect fun urlEncodeQuery(value: String): String
expect fun decodeBytes(bytes: ByteArray, charsetName: String?): String
expect fun base64Encode(bytes: ByteArray): String
expect fun base64Decode(str: String): ByteArray

// ---- URI helpers (java.net.URI semantics) ----
expect fun uriResolve(base: String, reference: String): String
expect fun uriHost(url: String): String?
expect fun uriPath(url: String): String
expect fun uriQuery(url: String): String?

// ---- misc ----
expect fun randomUuid(): String
expect val ioDispatcher: CoroutineContext

// ---- network failure localization (platform exception types) ----
expect fun describeNetworkFailure(e: Throwable): String?

// ---- closeable ----
expect interface Closeable {
    fun close()
}

// ---- Ktor HTTP engine (OkHttp is JVM/Android only) ----
expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): KtorClient
