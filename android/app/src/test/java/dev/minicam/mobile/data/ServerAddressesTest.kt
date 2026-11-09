package dev.minicam.mobile.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/adr/0013: local and remote address of one server, with a switch. */
class ServerAddressesTest {
    private val store = InMemoryCredentialStore()
    private val local = ServerConfig.parse("http://192.168.1.20:8080")
    private val remote = ServerConfig.parse("https://cam.example.com")

    private lateinit var lanA: MockWebServer

    @Before
    fun setUp() {
        lanA = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = lanA.close()

    @Test
    fun serverAddresses_migratesAndKeepsBoth() {
        store.set(CredentialKey.SERVER_URL, "https://cam.example.com")
        val addresses = ServerAddresses(store)
        assertEquals(remote, addresses.remote)
        assertEquals(remote, addresses.active)
        assertNull(addresses.local)

        addresses.add(local)
        assertEquals(local, addresses.local)
        assertEquals(remote, addresses.active)

        assertEquals(local, addresses.switchTo(Network.LOCAL))
        assertEquals(local, addresses.active)
        assertEquals("http://192.168.1.20:8080", store.get(CredentialKey.SERVER_URL))
        // A fresh instance (next app start) reads the same state.
        assertEquals(local, ServerAddresses(store).active)
        assertEquals(remote, ServerAddresses(store).remote)
    }

    @Test
    fun switchNetwork_keepsSessionAndUsesOtherOrigin() = runTest {
        val lan = ServerConfig.parse(lanA.url("/").toString())
        val addresses = ServerAddresses(store)
        addresses.use(remote)
        store.set(CredentialKey.SESSION_COOKIE, "s1")
        store.set(CredentialKey.ACCESS_TOKEN, "jwt")
        addresses.add(lan)

        val target = addresses.switchTo(Network.LOCAL)!!
        assertEquals(lan, target)
        assertTrue(addresses.use(target))
        assertEquals("s1", store.get(CredentialKey.SESSION_COOKIE))
        assertEquals("jwt", store.get(CredentialKey.ACCESS_TOKEN))

        lanA.enqueue(MockResponse.Builder().code(200).body("[]").build())
        MinicamApi(target, store).cameras()
        val request = lanA.takeRequest()
        assertEquals("minicam_session=s1", request.headers["Cookie"])
        // The Access token only ever goes to the remote address.
        assertNull(request.headers[MinicamApi.ACCESS_TOKEN_HEADER])
        // Back to remote: still the same session, no network call needed to switch.
        assertEquals(remote, addresses.switchTo(Network.REMOTE))
        assertEquals("s1", store.get(CredentialKey.SESSION_COOKIE))
    }

    @Test
    fun switchNetwork_toRemoteWithoutTokenNeedsAccess() {
        val addresses = ServerAddresses(store)
        addresses.use(local)
        addresses.add(remote)
        store.set(CredentialKey.SESSION_COOKIE, "s1")

        val target = addresses.switchTo(Network.REMOTE)!!
        assertTrue(addresses.needsAccessLogin(target))
        assertEquals("s1", store.get(CredentialKey.SESSION_COOKIE))
        store.set(CredentialKey.ACCESS_TOKEN, "jwt")
        assertFalse(addresses.needsAccessLogin(target))
        assertFalse(addresses.needsAccessLogin(addresses.switchTo(Network.LOCAL)!!))
    }

    @Test
    fun newServer_clearsEverything() {
        val addresses = ServerAddresses(store)
        addresses.use(local)
        addresses.add(remote)
        store.set(CredentialKey.SESSION_COOKIE, "s1")
        store.set(CredentialKey.ACCESS_TOKEN, "jwt")

        assertFalse(addresses.use(ServerConfig.parse("https://other.example.com")))
        assertNull(store.get(CredentialKey.SESSION_COOKIE))
        assertNull(store.get(CredentialKey.ACCESS_TOKEN))
        assertNull(addresses.local)
        assertEquals("https://other.example.com", addresses.active?.origin())
    }

    @Test
    fun logout_doesNotWaitForAnUnreachableServer() = runBlocking {
        lanA.enqueue(MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS).build())
        store.set(CredentialKey.SESSION_COOKIE, "s1")
        val api = MinicamApi(ServerConfig.parse(lanA.url("/").toString()), store)

        val started = System.nanoTime()
        api.logout()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(store.get(CredentialKey.SESSION_COOKIE))
        assertTrue("logout took $elapsedMs ms", elapsedMs < MinicamApi.LOGOUT_TIMEOUT_MS + 1_000)
        assertEquals("minicam_session=s1", lanA.takeRequest().headers["Cookie"])
    }
}
