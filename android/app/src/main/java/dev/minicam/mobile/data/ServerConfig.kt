package dev.minicam.mobile.data

import java.net.URI
import java.net.URLEncoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerConfigException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        INVALID_URL,
        UNSUPPORTED_SCHEME,

        /** `http://` was used for a host that isn't on the LAN (docs/SECURITY.md). */
        INSECURE_REMOTE_HOST,
    }
}

/**
 * The minicam server the app talks to. Only scheme, host and port are kept:
 * every minicam endpoint lives at the root of the host.
 */
data class ServerConfig(val baseUrl: HttpUrl) {

    /** `https` hosts go through Cloudflare Tunnel + Access; `http` is LAN only. */
    val isWan: Boolean get() = baseUrl.isHttps

    fun url(path: String): HttpUrl = "${origin()}$path".toHttpUrlOrNull()!!

    /** `ws(s)://host/ws/stream/{camera}?token=…`, scheme matching the server. */
    fun streamUrl(camera: String, token: String): String {
        val scheme = if (isWan) "wss" else "ws"
        val tokenParam = URLEncoder.encode(token, Charsets.UTF_8.name())
        return "$scheme://${hostPort()}/ws/stream/${encodePathSegment(camera)}?token=$tokenParam"
    }

    fun origin(): String = "${baseUrl.scheme}://${hostPort()}"

    private fun hostPort(): String {
        val defaultPort = HttpUrl.defaultPort(baseUrl.scheme)
        return if (baseUrl.port == defaultPort) baseUrl.host else "${baseUrl.host}:${baseUrl.port}"
    }

    companion object {
        fun parse(input: String): ServerConfig {
            val trimmed = input.trim()
            val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
                ?: throw ServerConfigException(ServerConfigException.Reason.INVALID_URL)
            if (scheme != "http" && scheme != "https") {
                throw ServerConfigException(ServerConfigException.Reason.UNSUPPORTED_SCHEME)
            }
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw ServerConfigException(ServerConfigException.Reason.INVALID_URL)
            if (!parsed.isHttps && !isLanHost(parsed.host)) {
                throw ServerConfigException(ServerConfigException.Reason.INSECURE_REMOTE_HOST)
            }
            val normalized = HttpUrl.Builder()
                .scheme(parsed.scheme)
                .host(parsed.host)
                .port(parsed.port)
                .build()
            return ServerConfig(normalized)
        }

        /** Private IPv4 ranges, loopback and mDNS `.local` names. */
        fun isLanHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split(".")
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
            return when {
                octets[0] == 10 || octets[0] == 127 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                else -> false
            }
        }

        fun encodePathSegment(segment: String): String =
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
