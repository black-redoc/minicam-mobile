package dev.minicam.mobile.data

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

class AccessHandoffException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_CALLBACK, STATE_MISMATCH, MISSING_TOKEN }
}

/**
 * Cloudflare Access login through Custom Tabs (docs/adr/0002).
 * One instance per login attempt: it owns the `state` the callback must echo.
 */
class AccessHandoff(val state: String = randomState()) {

    fun handoffUrl(config: ServerConfig): String =
        config.url("/api/mobile/access-handoff").newBuilder()
            .addQueryParameter("state", state)
            .build()
            .toString()

    /** Validates `minicam://access-callback?state=…#token=…` and returns the token. */
    fun tokenFromCallback(callback: String): String {
        val uri = runCatching { URI(callback) }.getOrNull()
        if (uri == null || uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST) {
            throw AccessHandoffException(AccessHandoffException.Reason.NOT_A_CALLBACK)
        }
        if (params(uri.rawQuery)["state"] != state) {
            throw AccessHandoffException(AccessHandoffException.Reason.STATE_MISMATCH)
        }
        return params(uri.rawFragment)["token"]?.takeIf { it.isNotEmpty() }
            ?: throw AccessHandoffException(AccessHandoffException.Reason.MISSING_TOKEN)
    }

    companion object {
        const val CALLBACK_SCHEME = "minicam"
        const val CALLBACK_HOST = "access-callback"

        /** 32 random bytes, base64url without padding (43 chars, matches spec 002 AC3). */
        fun randomState(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun params(raw: String?): Map<String, String> =
            raw.orEmpty().split("&").filter { it.contains("=") }.associate {
                val (name, value) = it.split("=", limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8.name()) to URLDecoder.decode(value, Charsets.UTF_8.name())
            }
    }
}
