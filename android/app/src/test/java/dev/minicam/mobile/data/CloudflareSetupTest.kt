package dev.minicam.mobile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** docs/specs/014: Cloudflare Tunnel setup (Cloudflare API + minicam's tunnel API). */
class CloudflareSetupTest {
    private lateinit var server: MockWebServer
    private val account = CloudflareAccount("acc1", "Home")
    private val zone = CloudflareZone("zone1", "example.com")
    private val aud = "a".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun client(token: String = "cf-api-token") =
        CloudflareClient(token, baseUrl = server.url("/client/v4/"))

    private fun ok(result: String) = enqueue(200, """{"success": true, "errors": [], "result": $result}""")

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(body!!.utf8())

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun RecordedRequest.line() = "$method $target"

    private suspend fun run() = CloudflareSetup(client()).run(account, zone, "cam.example.com", "admin@example.com")

    private fun requests(count: Int) = List(count) { server.takeRequest() }

    @Test
    fun cloudflareRequests_build() = runTest {
        ok("""{"auth_domain": "myteam.cloudflareaccess.com"}""")
        ok("[]")
        ok("""{"id": "tun1", "name": "minicam-cam.example.com"}""")
        ok("\"tunnel-token\"")
        ok("{}")
        ok("[]")
        ok("""{"id": "rec1"}""")
        ok("[]")
        ok("""{"id": "pol1", "name": "minicam-cam.example.com users"}""")
        ok("[]")
        ok("""{"id": "app1", "domain": "cam.example.com", "aud": "$aud"}""")

        val result = run()

        assertEquals(
            TunnelSetup("tunnel-token", "cam.example.com", "https://myteam.cloudflareaccess.com", aud),
            result,
        )
        val requests = requests(11)
        assertEquals(
            listOf(
                "GET /client/v4/accounts/acc1/access/organizations",
                "GET /client/v4/accounts/acc1/cfd_tunnel?name=minicam-cam.example.com&is_deleted=false",
                "POST /client/v4/accounts/acc1/cfd_tunnel",
                "GET /client/v4/accounts/acc1/cfd_tunnel/tun1/token",
                "PUT /client/v4/accounts/acc1/cfd_tunnel/tun1/configurations",
                "GET /client/v4/zones/zone1/dns_records?type=CNAME&name=cam.example.com",
                "POST /client/v4/zones/zone1/dns_records",
                "GET /client/v4/accounts/acc1/access/policies",
                "POST /client/v4/accounts/acc1/access/policies",
                "GET /client/v4/accounts/acc1/access/apps",
                "POST /client/v4/accounts/acc1/access/apps",
            ),
            requests.map { it.line() },
        )
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer cf-api-token" })
        assertEquals(json("""{"config_src": "cloudflare", "name": "minicam-cam.example.com"}"""), requests[2].json())
        assertEquals(
            json(
                """{"config": {"ingress": [
                    {"hostname": "cam.example.com", "service": "http://127.0.0.1:8080"},
                    {"service": "http_status:404"}]}}""",
            ),
            requests[4].json(),
        )
        assertEquals(
            json(
                """{"comment": "minicam tunnel", "content": "tun1.cfargotunnel.com",
                    "name": "cam.example.com", "proxied": true, "type": "CNAME"}""",
            ),
            requests[6].json(),
        )
        assertEquals(
            json(
                """{"decision": "allow", "include": [{"email": {"email": "admin@example.com"}}],
                    "name": "minicam-cam.example.com users"}""",
            ),
            requests[8].json(),
        )
        assertEquals(
            json(
                """{"domain": "cam.example.com", "name": "minicam cam.example.com",
                    "policies": [{"id": "pol1", "precedence": 1}], "session_duration": "24h",
                    "type": "self_hosted"}""",
            ),
            requests[10].json(),
        )
    }

    @Test
    fun cloudflareSetup_reusesExisting() = runTest {
        ok("""{"auth_domain": "myteam.cloudflareaccess.com"}""")
        ok("""[{"id": "tun1", "name": "minicam-cam.example.com"}]""")
        ok("\"tunnel-token\"")
        ok("{}")
        ok("""[{"id": "rec1", "content": "tun1.cfargotunnel.com", "proxied": true}]""")
        ok("""[{"id": "pol1", "name": "minicam-cam.example.com users"}]""")
        ok("""{"id": "pol1"}""")
        ok("""[{"id": "app1", "domain": "cam.example.com", "aud": "old"}]""")
        ok("""{"id": "app1", "domain": "cam.example.com", "aud": "$aud"}""")

        assertEquals(aud, run().aud)

        val lines = requests(9).map { it.line() }
        assertFalse(lines.any { it.startsWith("POST") })
        assertTrue("PUT /client/v4/accounts/acc1/access/policies/pol1" in lines)
        assertTrue("PUT /client/v4/accounts/acc1/access/apps/app1" in lines)
    }

    @Test
    fun cloudflareSetup_updatesARecordPointingElsewhere() = runTest {
        ok("""[{"id": "rec1", "content": "old.cfargotunnel.com", "proxied": true}]""")
        ok("""{"id": "rec1"}""")

        client().dnsRecord("zone1", "cam.example.com", "tun1")

        server.takeRequest()
        val patch = server.takeRequest()
        assertEquals("PATCH /client/v4/zones/zone1/dns_records/rec1", patch.line())
    }

    @Test
    fun cloudflareSetup_requiresZeroTrust() = runTest {
        enqueue(404, """{"success": false, "errors": [{"code": 9999, "message": "no org"}], "result": null}""")
        ok("""{"auth_domain": ""}""")

        repeat(2) {
            try {
                run()
                fail("expected ZeroTrustRequired")
            } catch (_: CloudflareException.ZeroTrustRequired) {
            }
        }
    }

    @Test
    fun cloudflareResponses_parse() = runTest {
        enqueue(
            403,
            """{"success": false, "errors": [{"code": 10000, "message": "Authentication error"}, {"code": 1, "message": "Bad token"}]}""",
        )
        enqueue(500, """{"success": false}""")
        enqueue(502, "<html>")
        ok("""[{"id": "acc1", "name": "Home"}]""")
        ok("""[{"id": "zone1", "name": "example.com"}]""")

        val client = client()
        assertEquals("Authentication error; Bad token", runCatching { client.accounts() }.exceptionOrNull()?.message)
        assertEquals("HTTP 500", runCatching { client.accounts() }.exceptionOrNull()?.message)
        assertEquals(CloudflareException.InvalidResponse, runCatching { client.accounts() }.exceptionOrNull())
        assertEquals(listOf(account), client.accounts())
        assertEquals(listOf(zone), client.zones("acc1"))
        val zonesRequest = requests(5).last()
        assertEquals("GET /client/v4/zones?account.id=acc1&per_page=50", zonesRequest.line())
    }

    @Test
    fun tunnelHostname_validates() {
        assertEquals("cam.example.com", TunnelHostname.make("Cam", "example.com"))
        assertEquals("my-cam.example.com", TunnelHostname.make(" my-cam ", "example.com"))
        for (bad in listOf("", "-cam", "cam-", "a.b", "cam_1", "a".repeat(64))) {
            assertNull(TunnelHostname.make(bad, "example.com"))
        }
    }

    @Test
    fun tunnelApi_postsAndPolls() = runTest {
        enqueue(202, "")
        enqueue(200, """{"available": true, "state": "installing", "hostname": "cam.example.com", "message": "Installing cloudflared"}""")
        enqueue(200, """{"available": true, "state": "running", "hostname": "cam.example.com", "message": null}""")
        enqueue(202, "")
        val api = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())
        val setup = TunnelSetup("tok", "cam.example.com", "https://myteam.cloudflareaccess.com", aud)

        api.setUpTunnel(setup)
        val status = api.waitForTunnel(sleep = {})
        api.removeTunnel()

        assertEquals(TunnelStatus(true, TunnelState.RUNNING, "cam.example.com"), status)
        val requests = requests(4)
        assertEquals(
            listOf("POST /api/system/tunnel", "GET /api/system/tunnel", "GET /api/system/tunnel", "DELETE /api/system/tunnel"),
            requests.map { it.line() },
        )
        assertEquals(
            json(
                """{"tunnel_token": "tok", "hostname": "cam.example.com",
                    "team_domain": "https://myteam.cloudflareaccess.com", "aud": "$aud"}""",
            ),
            requests[0].json(),
        )
    }

    @Test
    fun tunnelApi_stopsWaitingAtTimeout() = runTest {
        repeat(3) { enqueue(200, """{"available": true, "state": "installing"}""") }
        val api = MinicamApi(ServerConfig.parse(server.url("/").toString()), InMemoryCredentialStore())

        val status = api.waitForTunnel(intervalMillis = 1_000, timeoutMillis = 2_000, sleep = {})

        assertEquals(TunnelState.INSTALLING, status.state)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun cloudflareSetup_doesNotStoreToken() = runTest {
        val store = InMemoryCredentialStore()
        ok("[]")

        client("secret-cf-token").accounts()

        assertTrue(CredentialKey.entries.all { store.get(it) == null })
        assertFalse("secret-cf-token" in client("secret-cf-token").toString())
        val setup = TunnelSetup("tunnel-secret", "cam.example.com", "https://t.cloudflareaccess.com", aud)
        assertFalse("tunnel-secret" in setup.toString())
    }
}
