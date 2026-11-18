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

/** docs/specs/020 AC3/AC4: the device's version, its update and following one. */
class UpdateApiTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()
    private val statusJson = """
        {"available": true, "current": "v0.6.0", "latest": "v0.7.0", "update_available": true,
         "state": "idle", "version": null, "message": null, "checked_at": "2026-11-18T12:00:00+00:00"}
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

    @Test
    fun systemUpdate_parsesTheStatus() = runTest {
        enqueue(200, statusJson)

        val status = api().systemUpdate()

        assertEquals("/api/system/update", server.takeRequest().target)
        assertEquals("v0.6.0", status?.current)
        assertEquals("v0.7.0", status?.latest)
        assertTrue(status!!.updateAvailable)
        assertFalse(status.busy)
    }

    @Test
    fun systemUpdate_refreshAsksTheDeviceToCheckNow() = runTest {
        enqueue(200, statusJson)

        api().systemUpdate(refresh = true)

        assertEquals("/api/system/update?refresh=true", server.takeRequest().target)
    }

    @Test
    fun systemUpdate_olderMinicamHasNoUpdates() = runTest {
        enqueue(404, """{"detail": "Not Found"}""")

        assertNull(api().systemUpdate())
    }

    @Test
    fun startUpdate_postsTheVersion() = runTest {
        enqueue(202, statusJson.replace("\"idle\"", "\"requested\""))

        val status = api().startUpdate("v0.7.0")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"version":"v0.7.0"}""", request.body?.utf8())
        assertTrue(status.busy)
    }

    private fun status(current: String, state: String, message: String? = null) =
        SystemUpdate(available = true, current = current, state = state, message = message)

    @Test
    fun tracker_runsUntilTheDeviceAnswersWithTheNewVersion() {
        val tracker = UpdateTracker("v0.7.0", startedAtMillis = 0)

        assertEquals(
            UpdateProgress.Running("v0.7.0", "downloading", "Downloading"),
            tracker.onStatus(status("v0.6.0", "downloading", "Downloading"), 5_000),
        )
        assertEquals(UpdateProgress.Running("v0.7.0", "restarting", null), tracker.onUnreachable(60_000))
        assertEquals(UpdateProgress.Done("v0.7.0"), tracker.onStatus(status("v0.7.0", "done"), 90_000))
    }

    @Test
    fun tracker_anErrorEndsIt() {
        val tracker = UpdateTracker("v0.7.0", startedAtMillis = 0)

        assertEquals(
            UpdateProgress.Failed("minicam did not start; minicam v0.6.0 was restored"),
            tracker.onStatus(status("v0.6.0", "error", "minicam did not start; minicam v0.6.0 was restored"), 200_000),
        )
    }

    @Test
    fun tracker_idleOnTheOldVersionAfterTheGraceIsAFailure() {
        val tracker = UpdateTracker("v0.7.0", startedAtMillis = 0)

        assertEquals(
            UpdateProgress.Running("v0.7.0", "idle", null),
            tracker.onStatus(status("v0.6.0", "idle"), UpdateTracker.GRACE_MILLIS),
        )
        assertEquals(UpdateProgress.Failed(null), tracker.onStatus(status("v0.6.0", "idle"), UpdateTracker.GRACE_MILLIS + 1))
    }

    @Test
    fun tracker_givesUpAfterTheTimeout() {
        val tracker = UpdateTracker("v0.7.0", startedAtMillis = 0)

        assertEquals(UpdateProgress.Failed(null), tracker.onUnreachable(UpdateTracker.TIMEOUT_MILLIS + 1))
        assertEquals(
            UpdateProgress.Failed(null),
            tracker.onStatus(status("v0.6.0", "installing"), UpdateTracker.TIMEOUT_MILLIS + 1),
        )
    }
}
