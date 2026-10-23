package dev.minicam.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

/** One entry of `GET /api/me/cameras` (docs/specs/002). */
@Serializable
data class CameraSummary(
    val name: String,
    @SerialName("can_use_ptz") val canUsePtz: Boolean,
    /** Absent on servers older than minicam PR #21, which have no live audio. */
    @SerialName("can_listen") val canListen: Boolean = false,
)

/**
 * `POST /api/stream/{camera}/token`. The MIME types (with RFC 6381 codecs) are the camera's own
 * stream for `format=fmp4`, `null` while unknown (ADR 0006); [audioMime] also needs `can_listen`.
 */
@Serializable
data class StreamToken(
    val token: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("video_mime") val videoMime: String? = null,
    @SerialName("audio_mime") val audioMime: String? = null,
)

@Serializable
internal data class LoginRequest(val email: String, val password: String)

@Serializable
internal data class ErrorBody(val detail: String)

/** Mirrors `_ALLOWED_ACTIONS` in minicam `app/routers/ptz.py`. */
enum class PtzAction(val path: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"), STOP("stop"),
}

sealed class MinicamException(message: String) : Exception(message) {
    data object InvalidCredentials : MinicamException("Invalid email or password")

    /** The session is gone (expired, revoked, logged out); it has been cleared. */
    data object Unauthorized : MinicamException("Session expired")

    /** Cloudflare Access redirected or rejected the request: run the Access login again. */
    data object AccessRequired : MinicamException("Cloudflare Access login required")
    data class Forbidden(val detail: String) : MinicamException(detail)
    data object NotFound : MinicamException("Not found")
    data object RateLimited : MinicamException("Too many attempts")
    /** minicam refused the request and said why (its JSON `detail`). */
    data class Rejected(val detail: String) : MinicamException(detail)
    data class Server(val code: Int) : MinicamException("Server error $code")
    data object InvalidResponse : MinicamException("Unexpected server response")
}
