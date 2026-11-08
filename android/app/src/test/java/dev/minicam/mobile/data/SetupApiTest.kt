package dev.minicam.mobile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/specs/013: LAN discovery and first setup. */
class SetupApiTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun api() = MinicamApi(ServerConfig.parse(server.url("/").toString()), store)

    private fun enqueue(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build(),
        )
    }

    @Test
    fun discoveredServer_buildsLanBaseUrl() {
        assertEquals("http://192.168.1.37:8080", DiscoveredServers.baseUrl("192.168.1.37", 8080))
        assertEquals("http://minicam-sebas.local:8080", DiscoveredServers.baseUrl("minicam-sebas.local", 8080))
        assertNull(DiscoveredServers.baseUrl("8.8.8.8", 8080))
        assertNull(DiscoveredServers.baseUrl("example.com", 8080))
        assertNull(DiscoveredServers.baseUrl("192.168.1.37", 0))
    }

    @Test
    fun setupStatus_mapsToDeviceState() = runTest {
        enqueue(200, """{"setup_required": true, "claim": "led", "window_open": true, "window_seconds_left": 1200}""")
        val status = api().setupStatus()
        assertEquals(DeviceSetupState.READY, status.state)
        assertTrue(status.needsLedCode)
        assertEquals(1200, status.windowSecondsLeft)
        assertEquals("/api/auth/setup", server.takeRequest().target)

        val json = Json { ignoreUnknownKeys = true }
        fun state(body: String) = json.decodeFromString(SetupStatus.serializer(), body).state
        assertEquals(DeviceSetupState.RESTART_NEEDED, state("""{"setup_required": true, "claim": "led", "window_open": false}"""))
        assertEquals(DeviceSetupState.CONFIGURED, state("""{"setup_required": false, "claim": "led"}"""))
        assertEquals(DeviceSetupState.READY, state("""{"setup_required": true}"""))
        assertFalse(json.decodeFromString(SetupStatus.serializer(), """{"setup_required": true}""").needsLedCode)
    }

    @Test
    fun setupCode_validates() {
        assertTrue(SetupCode.isValid("2468"))
        for (bad in listOf("", "246", "24680", "2408", "24a8", " 246")) assertFalse(SetupCode.isValid(bad))
    }

    @Test
    fun setupClaim_andCompleteSetup() = runTest {
        enqueue(200, """{"digits": 4, "expires_in": 300}""")
        enqueue(
            201,
            """{"id": 1, "email": "admin@example.com", "is_admin": true}""",
            mapOf("Set-Cookie" to "minicam_session=abc; Path=/; HttpOnly"),
        )
        val api = api()

        assertEquals(SetupClaim(4, 300), api.requestSetupCode())
        val user = api.completeSetup("admin@example.com", "correct horse", "2468")

        assertEquals(User(1, "admin@example.com", true), user)
        assertEquals("abc", store.get(CredentialKey.SESSION_COOKIE))
        val claim = server.takeRequest()
        assertEquals("POST /api/auth/setup/claim", "${claim.method} ${claim.target}")
        val setup = server.takeRequest()
        assertEquals(
            Json.parseToJsonElement("""{"email": "admin@example.com", "password": "correct horse", "setup_code": "2468"}"""),
            Json.parseToJsonElement(setup.body!!.utf8()),
        )
    }

    @Test
    fun setupErrors_mapToMessages() = runTest {
        enqueue(403, """{"detail": "Setup is closed. Restart the device to set it up."}""")
        enqueue(403, """{"detail": "Wrong code. Ask the device for a new one."}""")
        enqueue(429, """{"detail": "Wait a few seconds before asking for a new code."}""")
        enqueue(409, """{"detail": "Setup already completed"}""")
        val api = api()

        assertEquals(
            MinicamException.Forbidden("Setup is closed. Restart the device to set it up."),
            runCatching { api.requestSetupCode() }.exceptionOrNull(),
        )
        assertEquals(
            MinicamException.Forbidden("Wrong code. Ask the device for a new one."),
            runCatching { api.completeSetup("a@b.c", "password1", "1111") }.exceptionOrNull(),
        )
        assertEquals(MinicamException.RateLimited, runCatching { api.requestSetupCode() }.exceptionOrNull())
        assertEquals(
            MinicamException.Rejected("Setup already completed"),
            runCatching { api.completeSetup("a@b.c", "password1", "1111") }.exceptionOrNull(),
        )
        assertNull(store.get(CredentialKey.SESSION_COOKIE))
    }
}
