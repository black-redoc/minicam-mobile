package dev.minicam.mobile.data

import kotlinx.serialization.Serializable

// Detection pushes to this phone (docs/adr/0007, minicam ADR 0011). minicam relays them through
// the central dashboard; it only answers `available: true` while paired and set up there.

@Serializable
data class MobilePushStatus(val available: Boolean)

@Serializable
internal data class MobilePushTokenRequest(val platform: String, val token: String, val key: String)

@Serializable
internal data class MobilePushUnregisterRequest(val token: String)

suspend fun MinicamApi.mobilePushStatus(): MobilePushStatus =
    get("/api/notifications/mobile/status", MobilePushStatus.serializer())

/** Registers this phone's FCM [token] and its AES-256 [key] (base64url) for the signed-in user. */
suspend fun MinicamApi.registerMobilePush(token: String, key: String) =
    sendJsonNoContent(
        "POST",
        MOBILE_PUSH_TOKENS,
        MobilePushTokenRequest.serializer(),
        MobilePushTokenRequest("android", token, key),
    )

suspend fun MinicamApi.unregisterMobilePush(token: String) =
    sendJsonNoContent("DELETE", MOBILE_PUSH_TOKENS, MobilePushUnregisterRequest.serializer(), MobilePushUnregisterRequest(token))

private const val MOBILE_PUSH_TOKENS = "/api/notifications/mobile/tokens"
