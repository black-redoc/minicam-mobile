package dev.minicam.mobile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** docs/specs/005: admin endpoints. */
class AdminApiTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()
    private val cameraJson = """
        {"name": "front door", "host": "192.168.1.100", "rtsp_port": 554, "onvif_port": 8000,
         "user": "admin", "password": "", "channel": 1, "stream": "sub",
         "detection_classes": ["person"], "notifications": "on"}
    """

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun api() = MinicamApi(ServerConfig.parse(server.url("/").toString()), store)

    private fun enqueue(code: Int, body: String = "") {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(body!!.utf8())

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun me_parsesAdminFlag() = runTest {
        enqueue(200, """{"id": 7, "email": "root@example.com", "is_admin": true}""")

        assertEquals(User(7, "root@example.com", true), api().me())
        assertEquals("/api/auth/me", server.takeRequest().target)
    }

    @Test
    fun adminCameras_parsesConfigs() = runTest {
        enqueue(200, "[$cameraJson]")

        val cameras = api().adminCameras()

        assertEquals(
            listOf(
                CameraConfig(
                    name = "front door",
                    host = "192.168.1.100",
                    onvifPort = 8000,
                    stream = StreamQuality.SUB,
                    detectionClasses = listOf("person"),
                    notifications = NotificationMode.ON,
                ),
            ),
            cameras,
        )
        assertEquals("/api/cameras", server.takeRequest().target)
    }

    @Test
    fun saveCamera_postsNewAndPutsExisting() = runTest {
        enqueue(201, cameraJson)
        enqueue(200, cameraJson)
        val camera = CameraConfig(name = "patio", host = "192.168.1.101", password = "secret")
        val api = api()

        api.saveCamera(camera, originalName = null)
        api.saveCamera(camera.copy(password = ""), originalName = "front door")

        val post = server.takeRequest()
        assertEquals("POST" to "/api/cameras", post.method to post.target)
        assertEquals(
            json(
                """{"name": "patio", "host": "192.168.1.101", "rtsp_port": 554, "onvif_port": 80,
                    "user": "admin", "password": "secret", "channel": 1, "stream": "main",
                    "detection_classes": [], "notifications": "inherit"}""",
            ),
            post.json(),
        )
        val put = server.takeRequest()
        assertEquals("PUT" to "/api/cameras/front%20door", put.method to put.target)
        assertEquals(json("\"\""), (put.json() as kotlinx.serialization.json.JsonObject)["password"])
    }

    @Test
    fun deleteCamera_sendsDelete() = runTest {
        enqueue(200, """{"status": "ok"}""")

        api().deleteCamera("front door")

        val request = server.takeRequest()
        assertEquals("DELETE" to "/api/cameras/front%20door", request.method to request.target)
    }

    @Test
    fun discover_parsesCameras() = runTest {
        enqueue(200, """[{"host": "192.168.1.105", "name": "IPCamera", "hardware": "IPC", "location": "x"}]""")

        assertEquals(listOf(DiscoveredCamera("192.168.1.105", "IPCamera", "IPC", "x")), api().discoverCameras())
        assertEquals("/api/setup/discover", server.takeRequest().target)
    }

    @Test
    fun detectionClasses_parsesClasses() = runTest {
        enqueue(200, """{"classes": ["person", "car"]}""")

        assertEquals(listOf("person", "car"), api().detectionClasses())
        assertEquals("/api/detection/classes", server.takeRequest().target)
    }

    @Test
    fun catalog_parsesAssets() = runTest {
        enqueue(
            200,
            """{"catalog_url": "https://example.com/manifest.json", "release": "v0.1.0", "assets": [
                {"name": "yolo11n-int8.onnx", "model": "yolo11n", "family": "YOLO11", "precision": "int8",
                 "profile": "Raspberry Pi 5 (CPU)", "bytes": 4590317, "sha256": "1ca2", "url": "https://x",
                 "installed": true, "active": true}]}""",
        )

        val catalog = api().modelCatalog()

        assertEquals("v0.1.0", catalog.release)
        assertEquals(
            CatalogAsset("yolo11n-int8.onnx", "yolo11n", "YOLO11", "int8", "Raspberry Pi 5 (CPU)", 4590317, true, true),
            catalog.assets.single(),
        )
    }

    @Test
    fun installCatalogModel_postsName() = runTest {
        enqueue(200, """{"model_path": "/m/yolo11n-int8.onnx", "sha256": "1ca2", "active": true}""")

        api().installCatalogModel("yolo11n-int8.onnx")

        val request = server.takeRequest()
        assertEquals("POST" to "/api/detection/catalog/install", request.method to request.target)
        assertEquals(json("""{"name": "yolo11n-int8.onnx", "activate": true}"""), request.json())
    }

    @Test
    fun detectionSettings_roundTrip() = runTest {
        val body = """{"backend": "onnx", "model_path": "/m/a.onnx", "confidence_threshold": 0.4, "inference_fps": 2.5}"""
        enqueue(200, body)
        enqueue(200, body)
        val api = api()

        val settings = api.detectionSettings()
        api.saveDetectionSettings(settings)

        assertEquals(DetectionSettings(DetectionBackend.ONNX, "/m/a.onnx", 0.4, 2.5), settings)
        assertEquals("/api/detection/settings", server.takeRequest().target)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(json(body), put.json())
    }

    @Test
    fun fetchModel_returnsModelPath() = runTest {
        enqueue(200, """{"model_path": "/m/b.onnx"}""")

        assertEquals("/m/b.onnx", api().fetchModel("https://example.com/b.onnx"))
        val request = server.takeRequest()
        assertEquals("/api/detection/model/fetch", request.target)
        assertEquals(json("""{"url": "https://example.com/b.onnx"}"""), request.json())
    }

    @Test
    fun users_listAndCreate() = runTest {
        enqueue(200, """[{"id": 1, "email": "a@example.com", "is_admin": true}]""")
        enqueue(201, """{"id": 2, "email": "b@example.com", "is_admin": false}""")
        val api = api()

        assertEquals(listOf(User(1, "a@example.com", true)), api.users())
        assertEquals(User(2, "b@example.com", false), api.createUser("b@example.com", "password1", false))

        assertEquals("/api/auth/users", server.takeRequest().target)
        val post = server.takeRequest()
        assertEquals("POST" to "/api/auth/users", post.method to post.target)
        assertEquals(json("""{"email": "b@example.com", "password": "password1", "is_admin": false}"""), post.json())
    }

    @Test
    fun deleteUser_sendsDelete() = runTest {
        enqueue(204)

        api().deleteUser(2)

        val request = server.takeRequest()
        assertEquals("DELETE" to "/api/auth/users/2", request.method to request.target)
    }

    @Test
    fun userAccess_parsesPermissionsAndWanAccess() = runTest {
        enqueue(200, """[{"id": 2, "email": "b@example.com", "is_admin": false}]""")
        // Parallel requests: answer by path, not by order.
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/auth/users" -> MockResponse.Builder()
                    .body("""[{"id": 2, "email": "b@example.com", "is_admin": false}]""").build()
                "/api/auth/permissions" -> MockResponse.Builder()
                    .body("""[{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false}]""").build()
                else -> MockResponse.Builder().body("""{"user_id": 2, "is_active": true}""").build()
            }
        }

        val access = api().userAccess()

        assertEquals(
            listOf(UserAccess(User(2, "b@example.com", false), true, listOf(CameraPermission(2, "patio", true, false)))),
            access,
        )
    }

    @Test
    fun setPermission_postsBody() = runTest {
        enqueue(200, """{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": true}""")

        api().setPermission(CameraPermission(2, "patio", canView = true, canUsePtz = true))

        val request = server.takeRequest()
        assertEquals("POST" to "/api/auth/permissions", request.method to request.target)
        assertEquals(
            json("""{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": true, "can_listen": false}"""),
            request.json(),
        )
    }

    /** KI-9: a missing can_listen is reset to false by the server, so it is always sent. */
    @Test
    fun setPermission_sendsCanListen() = runTest {
        enqueue(200, """{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false, "can_listen": true}""")

        api().setPermission(CameraPermission(2, "patio", canView = true, canUsePtz = false, canListen = true))

        assertEquals(
            json("""{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false, "can_listen": true}"""),
            server.takeRequest().json(),
        )
    }

    @Test
    fun setWanAccess_postsBody() = runTest {
        enqueue(200, """{"user_id": 2, "is_active": false}""")

        api().setWanAccess(2, active = false)

        val request = server.takeRequest()
        assertEquals("POST" to "/api/auth/wan-access", request.method to request.target)
        assertEquals(json("""{"user_id": 2, "is_active": false}"""), request.json())
    }

    @Test
    fun notificationSettings_roundTrip() = runTest {
        enqueue(200, """{"enabled": true, "cooldown_seconds": 120}""")
        enqueue(200, """{"enabled": false, "cooldown_seconds": 30}""")
        val api = api()

        assertEquals(NotificationSettings(true, 120), api.notificationSettings())
        assertEquals(NotificationSettings(false, 30), api.saveNotificationSettings(NotificationSettings(false, 30)))

        assertEquals("/api/notifications/settings", server.takeRequest().target)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(json("""{"enabled": false, "cooldown_seconds": 30}"""), put.json())
    }

    @Test
    fun errorDetail_isReported() = runTest {
        enqueue(409, """{"detail": "Email already registered"}""")
        enqueue(422, """{"detail": [{"loc": ["body", "rtsp_port"], "msg": "too big"}]}""")
        val api = api()

        val conflict = runCatching { api.createUser("a@example.com", "password1", false) }.exceptionOrNull()
        val invalid = runCatching { api.adminCameras() }.exceptionOrNull()

        assertEquals(MinicamException.Rejected("Email already registered"), conflict)
        // FastAPI's validation `detail` is a list, not a message.
        assertEquals(MinicamException.Server(422), invalid)
        assertNull(store.get(CredentialKey.SESSION_COOKIE))
    }
}
