package dev.minicam.mobile.data

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class MinicamApiTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()
    private val userJson = """{"id": 1, "email": "a@example.com", "is_admin": false}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    /** MockWebServer listens on 127.0.0.1, a LAN host for ServerConfig. */
    private fun lanApi() = MinicamApi(ServerConfig.parse(server.url("/").toString()), store)

    private fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body).apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.build(),
        )
    }

    @Test
    fun login_storesSessionCookie() = runTest {
        enqueue(200, userJson, "Set-Cookie" to "minicam_session=jwt123; HttpOnly; Path=/; SameSite=lax")

        val user = lanApi().login("a@example.com", "secret123")

        assertEquals(User(1, "a@example.com", false), user)
        assertEquals("jwt123", store.get(CredentialKey.SESSION_COOKIE))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/login", request.target)
        assertEquals("""{"email":"a@example.com","password":"secret123"}""", request.body?.utf8())
    }

    @Test
    fun login_unauthorized_reportsInvalidCredentials() = runTest {
        enqueue(401, """{"detail": "Invalid email or password"}""")
        val api = lanApi()

        val error = runCatching { api.login("a@example.com", "wrong") }.exceptionOrNull()

        assertEquals(MinicamException.InvalidCredentials, error)
    }

    @Test
    fun requests_carrySessionAndAccessHeaders() = runTest {
        enqueue(200, "[]")
        store.set(CredentialKey.SESSION_COOKIE, "jwt123")
        store.set(CredentialKey.ACCESS_TOKEN, "cf-jwt")
        // Same server, but treated as a WAN (https) config so the Access header is sent.
        val wanConfig = ServerConfig(server.url("/").newBuilder().scheme("https").build())
        val api = MinicamApi(wanConfig, store)
        val request = api.streamRequest("entrada", "st")

        assertEquals("minicam_session=jwt123", request.header("Cookie"))
        assertEquals("cf-jwt", request.header("cf-access-token"))

        lanApi().cameras()
        val lanRequest = server.takeRequest()
        assertEquals("minicam_session=jwt123", lanRequest.headers["Cookie"])
        assertNull(lanRequest.headers["cf-access-token"])
    }

    @Test
    fun unauthorized_clearsSession() = runTest {
        enqueue(401, """{"detail": "Not authenticated"}""")
        store.set(CredentialKey.SESSION_COOKIE, "jwt123")
        val api = lanApi()

        val error = runCatching { api.cameras() }.exceptionOrNull()

        assertEquals(MinicamException.Unauthorized, error)
        assertNull(store.get(CredentialKey.SESSION_COOKIE))
    }

    @Test
    fun cameras_parsesViewableCameras() = runTest {
        enqueue(200, """[{"name": "entrada", "can_use_ptz": true}, {"name": "patio", "can_use_ptz": false}]""")

        val cameras = lanApi().cameras()

        assertEquals(listOf(CameraSummary("entrada", true), CameraSummary("patio", false)), cameras)
        assertEquals("/api/me/cameras", server.takeRequest().target)
    }

    @Test
    fun ptz_postsAction() = runTest {
        val api = lanApi()
        PtzAction.entries.forEach { enqueue(200, "{}") }

        PtzAction.entries.forEach { api.ptz("front door", it) }

        val paths = PtzAction.entries.map { server.takeRequest().let { r -> r.method to r.target } }
        assertEquals(
            listOf("up", "down", "left", "right", "stop").map { "POST" to "/api/ptz/front%20door/$it" },
            paths,
        )
    }

    @Test
    fun streamToken_returnsToken() = runTest {
        enqueue(200, """{"token": "st", "expires_in": 30}""")

        assertEquals("st", lanApi().streamToken("entrada"))
        assertEquals("/api/stream/entrada/token", server.takeRequest().target)
    }

    @Test
    fun forbiddenWithDetail_isReported() = runTest {
        enqueue(403, """{"detail": "WAN access is not enabled for this account"}""")
        val api = lanApi()

        val error = runCatching { api.streamToken("entrada") }.exceptionOrNull()

        assertEquals(MinicamException.Forbidden("WAN access is not enabled for this account"), error)
    }

    @Test
    fun redirect_meansAccessRequired() = runTest {
        enqueue(302, "", "Location" to "https://team.cloudflareaccess.com/cdn-cgi/access/login")
        val api = lanApi()

        assertEquals(MinicamException.AccessRequired, runCatching { api.cameras() }.exceptionOrNull())
    }

    /** KI-6: Android forbids network I/O on the main thread, where viewModelScope runs. */
    @Test
    fun responseBody_isNeverReadOnTheCallersThread() = runTest {
        enqueue(200, "[]")
        val bodyThreads = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun responseBodyStart(call: Call) {
                        bodyThreads += Thread.currentThread().name
                    }
                },
            )
            .build()
        val api = MinicamApi(ServerConfig.parse(server.url("/").toString()), store, client)
        val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

        fakeMain.use { withContext(it) { api.cameras() } }

        assertEquals(1, bodyThreads.size)
        assertNotEquals("fake-main", bodyThreads.single())
    }

    @Test
    fun reconnectPolicy_waitsThreeSeconds() {
        assertEquals(3_000L, ReconnectPolicy().delayMillis)
    }

    @Test
    fun invalidServerUrl_throws() {
        assertThrows(ServerConfigException::class.java) { ServerConfig.parse("http://example.com") }
    }
}
