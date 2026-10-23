package dev.minicam.mobile.data

import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

// Event recordings (docs/specs/006, ADR 0005, minicam `app/docs/recordings.md`). The server
// returns only clips of cameras the user can view; delete and settings are admin-only.

/** Page size of the clip list, like the web Recordings page. */
const val RECORDINGS_PAGE_SIZE = 30

@Serializable
data class Recording(
    val id: Int,
    @SerialName("camera_name") val cameraName: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("size_bytes") val sizeBytes: Long,
    val classes: List<String> = emptyList(),
) {
    /** When the clip starts; the epoch if the server sent something unexpected. */
    val started: Instant get() = parseRecordingStart(startedAt) ?: Instant.EPOCH
}

/** `GET /api/recordings`: newest first; pass [nextBefore] as `before` for the next page. */
@Serializable
data class RecordingPage(
    val items: List<Recording>,
    @SerialName("next_before") val nextBefore: Int? = null,
)

@Serializable
data class StorageOption(val gb: Int, val available: Boolean)

/** `GET/PUT /api/recordings/settings` response: settings plus disk figures. */
@Serializable
data class RecordingSettings(
    val enabled: Boolean = false,
    @SerialName("max_storage_gb") val maxStorageGb: Int = 5,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long = 0,
    val options: List<StorageOption> = emptyList(),
)

@Serializable
internal data class RecordingConfig(
    val enabled: Boolean,
    @SerialName("max_storage_gb") val maxStorageGb: Int,
)

suspend fun MinicamApi.recordings(
    camera: String? = null,
    before: Int? = null,
    limit: Int = RECORDINGS_PAGE_SIZE,
): RecordingPage {
    val query = buildList {
        add("limit=$limit")
        camera?.let { add("camera=${ServerConfig.encodePathSegment(it)}") }
        before?.let { add("before=$it") }
    }
    return get("/api/recordings?${query.joinToString("&")}", RecordingPage.serializer())
}

/** Deletes a clip and its file (admin). Already gone counts as deleted, like the web. */
suspend fun MinicamApi.deleteRecording(id: Int) {
    try {
        sendEmpty("DELETE", "/api/recordings/$id")
    } catch (_: MinicamException.NotFound) {
    }
}

suspend fun MinicamApi.recordingSettings(): RecordingSettings =
    get("/api/recordings/settings", RecordingSettings.serializer())

suspend fun MinicamApi.saveRecordingSettings(enabled: Boolean, maxStorageGb: Int): RecordingSettings =
    sendJson(
        "PUT",
        "/api/recordings/settings",
        RecordingConfig.serializer(),
        RecordingConfig(enabled, maxStorageGb),
        RecordingSettings.serializer(),
    )

/** The clip's MP4 (HTTP Range supported); fetch it with [MinicamApi.authHeaders]. */
fun MinicamApi.recordingVideoUrl(id: Int, download: Boolean = false): HttpUrl =
    config.url("/api/recordings/$id/video${if (download) "?download=true" else ""}")

/** Writes the clip's MP4 into [out]. */
suspend fun MinicamApi.downloadRecording(id: Int, out: OutputStream) =
    download("/api/recordings/$id/video?download=true", out)

// -- Formatting (mirrors minicam `app/static/js/recordings.js`) ---------------

private val unsafeFileNameChars = Regex("[^A-Za-z0-9_-]+")

/** `2026-10-22T14:03:11.250000+00:00` (or without the fraction) as an instant. */
fun parseRecordingStart(text: String): Instant? =
    runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

/** `m:ss`, rounded to the second. */
fun formatClipDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToLong()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

fun formatClipSize(bytes: Long): String = when {
    bytes >= GIB -> "%.1f GB".format(Locale.US, bytes / GIB.toDouble())
    bytes >= MIB -> "%.1f MB".format(Locale.US, bytes / MIB.toDouble())
    else -> "${(bytes / 1024.0).roundToLong().coerceAtLeast(1)} KB"
}

/** Disk usage line on the Admin card: GB with one decimal, MB below 1 GB. */
fun formatStorageBytes(bytes: Long): String =
    if (bytes >= GIB) "%.1f GB".format(Locale.US, bytes / GIB.toDouble()) else "${(bytes / MIB.toDouble()).roundToLong()} MB"

/** The server's `Content-Disposition` name, suggested when saving a download. */
fun recordingFileName(recording: Recording): String {
    val camera = recording.cameraName.replace(unsafeFileNameChars, "-").trim('-').ifEmpty { "camera" }
    val started = recording.startedAt.take(19).replace(unsafeFileNameChars, "")
    return "minicam-$camera-$started.mp4"
}

/** One "Today" / "Yesterday" / date section of the clip list. */
data class RecordingDay(val date: LocalDate, val label: String, val recordings: List<Recording>)

/** Groups clips (newest first) by their local day, keeping their order. */
fun recordingDays(
    recordings: List<Recording>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    locale: Locale = Locale.getDefault(),
): List<RecordingDay> {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    return recordings.groupBy { it.started.atZone(zone).toLocalDate() }.map { (date, items) ->
        val label = when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dateFormat)
        }
        RecordingDay(date, label, items)
    }
}

private const val MIB = 1024L * 1024
private const val GIB = MIB * 1024

/** The clip below [current] in the newest-first list, i.e. the next older one (spec 008 AC5). */
fun nextRecording(items: List<Recording>, current: Recording): Recording? {
    val index = items.indexOfFirst { it.id == current.id }
    return if (index < 0) null else items.getOrNull(index + 1)
}
