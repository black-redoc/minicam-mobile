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
)

@Serializable
internal data class StreamTokenResponse(
    val token: String,
    @SerialName("expires_in") val expiresIn: Int,
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
