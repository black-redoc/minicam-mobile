package dev.minicam.mobile.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

// Admin-only endpoints (docs/specs/005, ADR 0004). minicam's `require_admin`
// is the authorization check; a non-admin gets `Forbidden`.

suspend fun MinicamApi.adminCameras(): List<CameraConfig> =
    get("/api/cameras", ListSerializer(CameraConfig.serializer()))

/** Adds [camera] when [originalName] is null, otherwise replaces that camera (rename included). */
suspend fun MinicamApi.saveCamera(camera: CameraConfig, originalName: String?): CameraConfig =
    if (originalName == null) {
        sendJson("POST", "/api/cameras", CameraConfig.serializer(), camera, CameraConfig.serializer())
    } else {
        sendJson("PUT", cameraPath(originalName), CameraConfig.serializer(), camera, CameraConfig.serializer())
    }

suspend fun MinicamApi.deleteCamera(name: String) = sendEmpty("DELETE", cameraPath(name))

suspend fun MinicamApi.discoverCameras(): List<DiscoveredCamera> =
    get("/api/setup/discover", ListSerializer(DiscoveredCamera.serializer()))

suspend fun MinicamApi.detectionClasses(): List<String> =
    get("/api/detection/classes", DetectionClassesResponse.serializer()).classes

suspend fun MinicamApi.detectionSettings(): DetectionSettings =
    get("/api/detection/settings", DetectionSettings.serializer())

suspend fun MinicamApi.saveDetectionSettings(settings: DetectionSettings): DetectionSettings =
    sendJson("PUT", "/api/detection/settings", DetectionSettings.serializer(), settings, DetectionSettings.serializer())

suspend fun MinicamApi.modelCatalog(): ModelCatalog = get("/api/detection/catalog", ModelCatalog.serializer())

/** Downloads (if needed), verifies and activates a catalog model on the server. */
suspend fun MinicamApi.installCatalogModel(name: String) {
    sendJson(
        "POST",
        "/api/detection/catalog/install",
        CatalogInstallRequest.serializer(),
        CatalogInstallRequest(name),
        ModelPathResponse.serializer(),
    )
}

/** The server downloads the `.onnx` at [url]; returns its path on the server. */
suspend fun MinicamApi.fetchModel(url: String): String =
    sendJson(
        "POST",
        "/api/detection/model/fetch",
        ModelFetchRequest.serializer(),
        ModelFetchRequest(url),
        ModelPathResponse.serializer(),
    ).modelPath

suspend fun MinicamApi.users(): List<User> = get("/api/auth/users", ListSerializer(User.serializer()))

suspend fun MinicamApi.createUser(email: String, password: String, isAdmin: Boolean): User =
    sendJson(
        "POST",
        "/api/auth/users",
        CreateUserRequest.serializer(),
        CreateUserRequest(email, password, isAdmin),
        User.serializer(),
    )

suspend fun MinicamApi.deleteUser(id: Int) = sendEmpty("DELETE", "/api/auth/users/$id")

suspend fun MinicamApi.permissions(userId: Int): List<CameraPermission> =
    get("/api/auth/permissions?user_id=$userId", ListSerializer(CameraPermission.serializer()))

suspend fun MinicamApi.wanAccess(userId: Int): Boolean =
    get("/api/auth/wan-access?user_id=$userId", WanAccess.serializer()).isActive

/** Every user with their WAN access and permissions, fetched in parallel like the web page. */
suspend fun MinicamApi.userAccess(): List<UserAccess> = coroutineScope {
    users().map { user ->
        async {
            val permissions = async { permissions(user.id) }
            UserAccess(user, wanAccess(user.id), permissions.await())
        }
    }.awaitAll()
}

suspend fun MinicamApi.setPermission(permission: CameraPermission) {
    sendJson("POST", "/api/auth/permissions", CameraPermission.serializer(), permission, CameraPermission.serializer())
}

suspend fun MinicamApi.setWanAccess(userId: Int, active: Boolean) {
    sendJson("POST", "/api/auth/wan-access", WanAccess.serializer(), WanAccess(userId, active), WanAccess.serializer())
}

suspend fun MinicamApi.notificationSettings(): NotificationSettings =
    get("/api/notifications/settings", NotificationSettings.serializer())

suspend fun MinicamApi.saveNotificationSettings(settings: NotificationSettings): NotificationSettings =
    sendJson(
        "PUT",
        "/api/notifications/settings",
        NotificationSettings.serializer(),
        settings,
        NotificationSettings.serializer(),
    )

private fun cameraPath(name: String) = "/api/cameras/${ServerConfig.encodePathSegment(name)}"
