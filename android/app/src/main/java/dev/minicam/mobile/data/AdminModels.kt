package dev.minicam.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Admin API payloads (docs/specs/005, minicam `app/models/config.py` and `app/models/auth.py`).

/** Per-camera notification override: `INHERIT` follows the global switch. */
@Serializable
enum class NotificationMode {
    @SerialName("inherit") INHERIT,
    @SerialName("on") ON,
    @SerialName("off") OFF,
}

/**
 * `GET /api/cameras` item and the `POST`/`PUT` body. The server always returns
 * `password = ""`; sending it empty on `PUT` keeps the current password.
 */
@Serializable
data class CameraConfig(
    val name: String,
    val host: String,
    @SerialName("rtsp_port") val rtspPort: Int = 554,
    @SerialName("onvif_port") val onvifPort: Int = 80,
    val user: String = "admin",
    val password: String = "",
    val channel: Int = 1,
    @SerialName("detection_classes") val detectionClasses: List<String> = emptyList(),
    val notifications: NotificationMode = NotificationMode.INHERIT,
)

/** A camera found by `GET /api/setup/discover` (WS-Discovery). */
@Serializable
data class DiscoveredCamera(
    val host: String,
    val name: String,
    val hardware: String = "",
    val location: String = "",
)

@Serializable
internal data class DetectionClassesResponse(val classes: List<String>)

@Serializable
enum class DetectionBackend {
    @SerialName("none") NONE,
    @SerialName("onnx") ONNX,
}

/** `GET/PUT /api/detection/settings`. */
@Serializable
data class DetectionSettings(
    val backend: DetectionBackend = DetectionBackend.NONE,
    @SerialName("model_path") val modelPath: String = "",
    @SerialName("confidence_threshold") val confidenceThreshold: Double = 0.5,
    @SerialName("inference_fps") val inferenceFps: Double = 3.0,
)

/** One asset of the minicam-models release, with its install state on the server. */
@Serializable
data class CatalogAsset(
    val name: String,
    val model: String = "",
    val family: String = "",
    val precision: String = "",
    val profile: String = "",
    val bytes: Long = 0,
    val installed: Boolean = false,
    val active: Boolean = false,
)

@Serializable
data class ModelCatalog(
    val release: String,
    val assets: List<CatalogAsset>,
)

@Serializable
internal data class CatalogInstallRequest(val name: String, val activate: Boolean = true)

@Serializable
internal data class ModelFetchRequest(val url: String)

@Serializable
internal data class ModelPathResponse(@SerialName("model_path") val modelPath: String)

@Serializable
internal data class CreateUserRequest(
    val email: String,
    val password: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

@Serializable
data class CameraPermission(
    @SerialName("user_id") val userId: Int,
    @SerialName("camera_name") val cameraName: String,
    @SerialName("can_view") val canView: Boolean,
    @SerialName("can_use_ptz") val canUsePtz: Boolean,
    // Always sent: the server resets a missing can_listen to false (KI-9).
    @SerialName("can_listen") val canListen: Boolean = false,
)

@Serializable
internal data class WanAccess(
    @SerialName("user_id") val userId: Int,
    @SerialName("is_active") val isActive: Boolean,
)

/** Global detection notifications, `GET/PUT /api/notifications/settings`. */
@Serializable
data class NotificationSettings(
    val enabled: Boolean = false,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int = 60,
)

/** A user as the Admin screen shows it: account, WAN access and camera permissions. */
data class UserAccess(
    val user: User,
    val wanAccess: Boolean,
    val permissions: List<CameraPermission>,
)
