package cn.bit101.bitlogin.http

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import cn.bit101.bitlogin.util.currentTimeMillis

/**
 * Cookie storage that mirrors Python `requests.Session.cookies.get_dict()`:
 * all cookies ever stored are exposed via [snapshot] / [asMap], regardless of
 * URL matching rules. Each request still receives only matching cookies via
 * [get] (host/domain/path/expires matching, mirroring AcceptAllCookiesStorage).
 */
class TrackingCookieStorage : CookiesStorage {

    private val lock = Any()
    private val container = mutableListOf<Entry>()
    @Volatile private var oldestCookie: Long = Long.MAX_VALUE

    private data class Entry(val cookie: Cookie, val createdAt: Long)

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val now = currentTimeMillis()
        cleanupIfNeeded(now)
        return synchronized(lock) {
            container
                .asSequence()
                .map { it.cookie }
                .filter { !isExpired(it, now) }
                .filter { matches(it, requestUrl) }
                .toList()
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        val now = currentTimeMillis()
        val normalized = if (cookie.domain == null) cookie.copy(domain = requestUrl.host) else cookie
        synchronized(lock) {
            container.removeAll {
                it.cookie.name == normalized.name &&
                    it.cookie.domain == normalized.domain &&
                    it.cookie.path == normalized.path
            }
            container.add(Entry(normalized, now))
            oldestCookie = minOf(oldestCookie, normalized.expires?.timestamp ?: Long.MAX_VALUE)
        }
    }

    override fun close() {
        // no-op; resources are in-memory only.
    }

    fun snapshot(): List<Cookie> {
        val now = currentTimeMillis()
        synchronized(lock) {
            return container.asSequence().map { it.cookie }.filter { !isExpired(it, now) }.toList()
        }
    }

    fun asMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        snapshot().forEach { out[it.name] = it.value }
        return out
    }

    private suspend fun cleanupIfNeeded(now: Long) {
        if (now < oldestCookie) return
        synchronized(lock) {
            container.removeAll { isExpired(it.cookie, now) }
            oldestCookie = container
                .mapNotNull { it.cookie.expires?.timestamp }
                .minOrNull() ?: Long.MAX_VALUE
        }
    }

    private fun isExpired(cookie: Cookie, now: Long): Boolean {
        val expires = cookie.expires ?: return false
        if (expires.timestamp <= 0L) return false
        return expires.timestamp <= now
    }

    private fun matches(cookie: Cookie, requestUrl: Url): Boolean {
        val host = requestUrl.host
        val domain = cookie.domain ?: return false
        if (cookie.secure && requestUrl.protocol.name != "https") return false
        val hostMatches = host == domain || (domain.startsWith(".") && host.endsWith(domain.substring(1))) ||
            host.endsWith(".$domain")
        if (!hostMatches) return false
        val path = cookie.path ?: "/"
        val requestPath = requestUrl.encodedPath.ifEmpty { "/" }
        return requestPath == path ||
            requestPath.startsWith(path) && (path.endsWith("/") || requestPath.substring(path.length).startsWith("/"))
    }
}
