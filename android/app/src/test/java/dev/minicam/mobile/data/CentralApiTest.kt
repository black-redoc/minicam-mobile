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

/** minicam ADR 0009: pair the device with the central dashboard from the app. */
class CentralApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun api() = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun centralInput_normalizes() {
        assertEquals("https://admin.example.com", CentralUrl.normalize(" admin.example.com/ "))
        assertEquals("https://admin.example.com:8443", CentralUrl.normalize("https://Admin.Example.com:8443"))
        for (bad in listOf("http://admin.example.com", "https://admin.example.com/api", "https://u@a.com", "")) {
            assertNull(bad, CentralUrl.normalize(bad))
        }
        assertEquals("ABCDEFGH", PairingCode.normalize("abcd-efgh"))
        assertEquals("ABCDEFGH", PairingCode.normalize(" ABCD EFGH "))
        for (bad in listOf("ABCD-EFG", "ABCD-EFGHJ", "ABCD;EFGH", "")) assertNull(bad, PairingCode.normalize(bad))
    }

    @Test
    fun pairingCode_insertsDashWhileTyping() {
        assertEquals("ABCD", PairingCode.format("abcd"))
        assertEquals("ABCD-E", PairingCode.format("abcde"))
        assertEquals("ABCD-EFGH", PairingCode.format("ab cd-ef;ghij"))
        assertEquals("ABCDEFGH", PairingCode.clean("abcd-efghij"))
        assertEquals("ABCDFGH", PairingCode.clean("ABCDÉFGH"))
    }

    @Test
    fun centralApi_pairsReadsAndUnpairs() = runTest {
        enqueue(200, """{"connected": false, "url": null, "source": null, "last_synced_at": null}""")
        enqueue(
            200,
            """{"connected": true, "url": "https://admin.example.com", "source": "app", "last_synced_at": null,
                "site": {"name": "Casa", "hostname": "cam.example.com"}}""",
        )
        enqueue(200, """{"connected": false, "url": null, "source": null, "last_synced_at": null}""")
        val api = api()

        assertFalse(api.centralStatus().connected)
        val paired = api.pairWithCentral("https://admin.example.com", "ABCDEFGH")
        val unpaired = api.unpairFromCentral()

        assertEquals(PairedSite("Casa", "cam.example.com"), paired.site)
        assertEquals("app", paired.source)
        assertFalse(paired.managedInEnv)
        assertFalse(unpaired.connected)
        val requests = List(3) { server.takeRequest() }
        assertEquals(
            listOf("GET /api/system/central", "POST /api/system/central", "DELETE /api/system/central"),
            requests.map { "${it.method} ${it.target}" },
        )
        assertEquals(
            Json.parseToJsonElement("""{"url": "https://admin.example.com", "pairing_code": "ABCDEFGH"}"""),
            Json.parseToJsonElement(requests[1].body!!.utf8()),
        )
    }

    @Test
    fun centralApi_mapsErrors() = runTest {
        enqueue(502, """{"detail": "The dashboard did not accept the code."}""")
        enqueue(409, """{"detail": "This device's dashboard is set in its .env file; change it there instead"}""")
        enqueue(200, """{"connected": true, "url": "https://env.example.com", "source": "env", "last_synced_at": "2026-11-08T12:00:00.000Z"}""")
        val api = api()

        assertEquals(
            MinicamException.Rejected("The dashboard did not accept the code."),
            runCatching { api.pairWithCentral("https://admin.example.com", "ABCDEFGH") }.exceptionOrNull(),
        )
        assertTrue(runCatching { api.unpairFromCentral() }.exceptionOrNull() is MinicamException.Rejected)
        assertTrue(api.centralStatus().managedInEnv)
        assertFalse("ABCDEFGH" in CentralPairRequest("https://a.b", "ABCDEFGH").toString())
    }
}
