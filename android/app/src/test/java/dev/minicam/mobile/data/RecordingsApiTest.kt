package dev.minicam.mobile.data

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** docs/specs/006: event recordings. */
class RecordingsApiTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()
    private val recordingJson = """
        {"id": 42, "camera_name": "front door", "started_at": "2026-10-22T14:03:11.250000+00:00",
         "duration_seconds": 18.4, "size_bytes": 5242880, "classes": ["person"]}
    """
    private val settingsJson = """
        {"enabled": true, "max_storage_gb": 10, "used_bytes": 734003200, "free_bytes": 21474836480,
         "options": [{"gb": 1, "available": true}, {"gb": 50, "available": false}]}
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

    private fun recording(id: Int, startedAt: String) =
        Recording(id, "cam", startedAt, durationSeconds = 1.0, sizeBytes = 1, classes = emptyList())

    @Test
    fun recordings_parsesPage() = runTest {
        enqueue(200, """{"items": [$recordingJson], "next_before": 42}""")

        val page = api().recordings()

        assertEquals(
            RecordingPage(
                listOf(Recording(42, "front door", "2026-10-22T14:03:11.250000+00:00", 18.4, 5242880, listOf("person"))),
                nextBefore = 42,
            ),
            page,
        )
        assertEquals("/api/recordings?limit=30", server.takeRequest().target)
    }

    @Test
    fun recordings_sendsFilters() = runTest {
        enqueue(200, """{"items": [], "next_before": null}""")

        val page = api().recordings(camera = "front door", before = 42)

        assertNull(page.nextBefore)
        assertEquals("/api/recordings?limit=30&camera=front%20door&before=42", server.takeRequest().target)
    }

    @Test
    fun deleteRecording_sendsDelete() = runTest {
        enqueue(204)
        enqueue(404, """{"detail": "Not found"}""")
        val api = api()

        api.deleteRecording(42)
        // Already deleted (by another admin or by retention) is not an error.
        api.deleteRecording(43)

        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/recordings/42", it.target)
        }
        assertEquals("/api/recordings/43", server.takeRequest().target)
    }

    @Test
    fun recordingSettings_roundTrip() = runTest {
        enqueue(200, settingsJson)
        enqueue(200, settingsJson)
        enqueue(400, """{"detail": "Not enough disk space for 50 GB"}""")
        val api = api()

        val settings = api.recordingSettings()
        api.saveRecordingSettings(enabled = true, maxStorageGb = 10)
        val error = runCatching { api.saveRecordingSettings(enabled = true, maxStorageGb = 50) }.exceptionOrNull()

        assertEquals(
            RecordingSettings(true, 10, 734003200, 21474836480, listOf(StorageOption(1, true), StorageOption(50, false))),
            settings,
        )
        assertEquals("/api/recordings/settings", server.takeRequest().target)
        server.takeRequest().let {
            assertEquals("PUT", it.method)
            assertEquals(Json.parseToJsonElement("""{"enabled": true, "max_storage_gb": 10}"""), Json.parseToJsonElement(it.body!!.utf8()))
        }
        assertEquals(MinicamException.Rejected("Not enough disk space for 50 GB"), error)
    }

    @Test
    fun recordingVideoRequest_isAuthorized() = runTest {
        store.set(CredentialKey.SESSION_COOKIE, "abc")
        enqueue(200, "mp4-bytes")
        val api = api()
        val out = ByteArrayOutputStream()

        api.downloadRecording(42, out)

        assertEquals("mp4-bytes", out.toString(Charsets.UTF_8))
        server.takeRequest().let {
            assertEquals("/api/recordings/42/video?download=true", it.target)
            assertEquals("minicam_session=abc", it.headers["Cookie"])
        }
        assertEquals(server.url("/api/recordings/42/video"), api.recordingVideoUrl(42))
        assertEquals(mapOf("Cookie" to "minicam_session=abc"), api.authHeaders())
        // The Access token only goes to WAN hosts.
        store.set(CredentialKey.ACCESS_TOKEN, "jwt")
        val wan = MinicamApi(ServerConfig.parse("https://cam.example.com"), store)
        assertEquals(mapOf("Cookie" to "minicam_session=abc", "cf-access-token" to "jwt"), wan.authHeaders())
    }

    @Test
    fun recordingStart_parsesIsoTimestamps() {
        assertEquals(Instant.parse("2026-10-22T14:03:11.250Z"), parseRecordingStart("2026-10-22T14:03:11.250000+00:00"))
        assertEquals(Instant.parse("2026-10-22T19:03:11Z"), parseRecordingStart("2026-10-22T14:03:11-05:00"))
        assertNull(parseRecordingStart("yesterday"))
        assertEquals(
            "minicam-front-door-2026-10-22T140311.mp4",
            recordingFileName(recording(1, "2026-10-22T14:03:11.250000+00:00").copy(cameraName = "front door")),
        )
    }

    @Test
    fun clipDuration_formats() {
        assertEquals("0:18", formatClipDuration(18.4))
        assertEquals("1:05", formatClipDuration(64.6))
        assertEquals("0:00", formatClipDuration(-1.0))
    }

    @Test
    fun clipSize_formats() {
        assertEquals("1 KB", formatClipSize(10))
        assertEquals("500 KB", formatClipSize(512_000))
        assertEquals("5.0 MB", formatClipSize(5_242_880))
        assertEquals("1.5 GB", formatClipSize(1_610_612_736))
    }

    @Test
    fun storageBytes_formats() {
        assertEquals("700 MB", formatStorageBytes(734_003_200))
        assertEquals("20.0 GB", formatStorageBytes(21_474_836_480))
    }

    @Test
    fun recordingDays_groupByLocalDay() {
        val zone = ZoneId.of("America/Bogota")
        val items = listOf(
            recording(4, "2026-10-22T14:00:00+00:00"),
            // 01:00 UTC on the 22nd is still the 21st in Bogotá (UTC-5).
            recording(3, "2026-10-22T01:00:00+00:00"),
            recording(2, "2026-10-21T13:00:00+00:00"),
            recording(1, "2026-10-19T13:00:00+00:00"),
        )

        val days = recordingDays(items, zone, today = LocalDate.of(2026, 10, 22), locale = Locale.US)

        assertEquals(listOf("Today", "Yesterday", "Monday, October 19, 2026"), days.map { it.label })
        assertEquals(listOf(listOf(4), listOf(3, 2), listOf(1)), days.map { day -> day.recordings.map { it.id } })
    }

    @Test
    fun nextRecording_isTheOneBelow() {
        val items = listOf(recording(3, "2026-10-22T14:00:00+00:00"), recording(2, "2026-10-22T13:00:00+00:00"))

        assertEquals(2, nextRecording(items, items[0])?.id)
        assertNull(nextRecording(items, items[1]))
        assertNull(nextRecording(items, recording(9, "2026-10-22T12:00:00+00:00")))
    }
}
