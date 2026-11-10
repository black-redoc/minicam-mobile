package dev.minicam.mobile.data

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
import java.io.File

/** docs/adr/0011: connect to the dashboard with a code or its QR deep link. */
class ConnectFlowTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun api() = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())

    private fun enqueue(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private val paired = """{"connected": true, "url": "https://admin.example.com", "source": "app",
        "last_synced_at": null, "site": {"name": "Casa", "hostname": "cam.example.com"}"""

    @Test
    fun pairLink_parses() {
        assertEquals(
            PairLink("https://admin.example.com", "ABCDEFGH"),
            PairLink.parse("minicam://pair?url=https%3A%2F%2Fadmin.example.com&code=ABCDEFGH"),
        )
        assertEquals(
            PairLink("https://admin.example.com:8443", "ABCDEFGH"),
            PairLink.parse("minicam://pair?code=abcd-efgh&url=https://Admin.example.com:8443"),
        )
        assertFalse(PairLink("https://a.example.com", "ABCDEFGH").toString().contains("ABCDEFGH"))
    }

    @Test
    fun pairLink_rejectsInvalid() {
        for (bad in listOf(
            "https://pair?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://access-callback?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://pair/extra?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=http://admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com/api&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com&code=ABCD",
            "minicam://pair?url=https://admin.example.com",
            "minicam://pair?url=https://admin.example.com&code=ABCDEFGH&next=x",
            "minicam://pair?url=https://a.example.com&url=https://b.example.com&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com&code=ABCDEFGH#frag",
            "not a link",
        )) {
            assertNull(bad, PairLink.parse(bad))
        }
    }

    @Test
    fun pendingPairLink_expires() {
        var now = 0L
        val pending = PendingPairLink { now }
        val link = PairLink("https://admin.example.com", "ABCDEFGH")

        pending.store(link)
        now = PendingPairLink.TTL_MILLIS - 1
        assertEquals(link, pending.peek())
        assertEquals(link, pending.take())
        assertNull("taking clears it", pending.take())

        pending.store(link)
        now += PendingPairLink.TTL_MILLIS
        assertNull(pending.peek())
        assertNull(pending.take())
    }

    @Test
    fun centralApi_parsesTunnelResult() = runTest {
        enqueue("""$paired, "tunnel": {"state": "installing", "hostname": "cam.example.com", "message": null, "replaced": true}}""")
        enqueue("""$paired, "tunnel": null}""")
        enqueue("""$paired}""")
        val api = api()

        assertEquals(CentralTunnel("installing", "cam.example.com", null, replaced = true), api.pairWithCentral("https://admin.example.com", "ABCDEFGH").tunnel)
        assertNull(api.pairWithCentral("https://admin.example.com", "ABCDEFGH").tunnel)
        assertNull(api.pairWithCentral("https://admin.example.com", "ABCDEFGH").tunnel)

        val base = ConnectProgress()
        val status = CentralStatus(connected = true)
        assertEquals(StepState.SKIPPED, base.afterPairing(status).remoteAccess)
        assertEquals(
            StepState.SKIPPED,
            base.afterPairing(status.copy(tunnel = CentralTunnel("skipped", "h.example.com", "set in .env"))).remoteAccess,
        )
        val failed = base.afterPairing(status.copy(tunnel = CentralTunnel("error", null, "no helper")))
        assertEquals(StepState.FAILED to "no helper", failed.remoteAccess to failed.remoteAccessText)
        assertEquals(StepState.DONE, failed.dashboard)
    }

    @Test
    fun connectFlow_pollsTunnel() = runTest {
        enqueue("""$paired, "tunnel": {"state": "installing", "hostname": "cam.example.com"}}""")
        enqueue("""{"available": true, "state": "installing", "hostname": "cam.example.com", "source": "central"}""")
        enqueue("""{"available": true, "state": "running", "hostname": "cam.example.com", "source": "central"}""")
        val seen = mutableListOf<ConnectProgress>()

        val result = api().connectToDashboard(
            "https://admin.example.com",
            "ABCDEFGH",
            onProgress = { seen += it },
            wait = { waitForTunnel(sleep = {}) },
        )

        assertEquals(StepState.DONE, result.dashboard)
        assertEquals(StepState.DONE, result.remoteAccess)
        assertEquals(
            listOf(StepState.WAITING, StepState.WORKING, StepState.DONE),
            seen.map { it.remoteAccess },
        )
        val requests = List(3) { server.takeRequest() }.map { "${it.method} ${it.target}" }
        assertEquals(listOf("POST /api/system/central", "GET /api/system/tunnel", "GET /api/system/tunnel"), requests)
    }

    @Test
    fun connectFlow_reportsWanUrl() = runTest {
        enqueue("""$paired, "tunnel": {"state": "installing", "hostname": "cam.example.com"}}""")
        enqueue("""{"available": true, "state": "running", "hostname": "cam.example.com", "source": "central"}""")
        val running = api().connectToDashboard("https://admin.example.com", "ABCDEFGH")
        assertEquals("https://cam.example.com", running.wanUrl)

        enqueue("""$paired, "tunnel": {"state": "installing", "hostname": "cam.example.com"}}""")
        enqueue("""{"available": true, "state": "error", "hostname": "cam.example.com", "message": "apt failed"}""")
        val failed = api().connectToDashboard("https://admin.example.com", "ABCDEFGH")
        assertNull(failed.wanUrl)
        assertEquals(StepState.FAILED to "apt failed", failed.remoteAccess to failed.remoteAccessText)

        enqueue("""$paired}""")
        val noTunnel = api().connectToDashboard("https://admin.example.com", "ABCDEFGH")
        assertNull(noTunnel.wanUrl)
        assertEquals(StepState.SKIPPED, noTunnel.remoteAccess)
    }

    @Test
    fun manifest_registersPairLink() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""<data android:scheme="minicam" android:host="pair" />"""))
    }
}
