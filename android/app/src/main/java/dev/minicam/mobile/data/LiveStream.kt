package dev.minicam.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What the stream WebSocket carries (ADR 0006). */
enum class StreamFormat { MJPEG, FMP4 }

/** The format (and whether audio is on) one stream session uses. */
data class StreamChoice(val format: StreamFormat, val audio: Boolean, val mime: String?)

/**
 * fMP4 when the server knows the camera's codec and the device can decode it ([canPlay] takes
 * the sample MIME types of [LiveCodecs.sampleMimes]); audio only if also asked for and offered.
 * MJPEG otherwise, and always when [preferFmp4] is off (after an fMP4 playback failure).
 */
fun chooseStream(token: StreamToken, preferFmp4: Boolean, audio: Boolean, canPlay: (List<String>) -> Boolean): StreamChoice {
    val video = token.videoMime
    if (!preferFmp4 || video == null) return StreamChoice(StreamFormat.MJPEG, audio = false, mime = null)
    val withAudio = token.audioMime
    if (audio && withAudio != null && LiveCodecs.sampleMimes(withAudio)?.let(canPlay) == true) {
        return StreamChoice(StreamFormat.FMP4, audio = true, mime = withAudio)
    }
    if (LiveCodecs.sampleMimes(video)?.let(canPlay) == true) return StreamChoice(StreamFormat.FMP4, audio = false, mime = video)
    return StreamChoice(StreamFormat.MJPEG, audio = false, mime = null)
}

object LiveCodecs {
    /**
     * The decoder MIME types for `video/mp4; codecs="hvc1.1.6.L90,mp4a.40.2"`, or `null` if the
     * MIME has no codecs or one the app can't play.
     */
    fun sampleMimes(mime: String): List<String>? {
        val codecs = Regex("""codecs\s*=\s*"?([^";]+)"?""").find(mime)?.groupValues?.get(1) ?: return null
        return codecs.split(",").map { codec ->
            when (codec.trim().substringBefore('.')) {
                "avc1", "avc3" -> "video/avc"
                "hvc1", "hev1" -> "video/hevc"
                "mp4a" -> "audio/mp4a-latm"
                else -> return null
            }
        }
    }
}

/**
 * Keeps live playback near the newest frame: a new viewer starts at the latest keyframe (up to a
 * GOP behind) and any stall adds delay, so play faster while too much is buffered.
 */
object LiveCatchUp {
    const val FAST = 1.5f
    const val BEHIND_MS = 1_000L
    const val CAUGHT_UP_MS = 300L

    fun speed(bufferedMs: Long, current: Float): Float = when {
        bufferedMs > BEHIND_MS -> FAST
        bufferedMs < CAUGHT_UP_MS -> 1f
        else -> current
    }
}

/** One box of a `detections` message, in frame pixels. */
@Serializable
data class DetectionBox(
    val label: String,
    val confidence: Float,
    val color: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) {
    /**
     * Where the box lands in a view of [viewWidth] x [viewHeight] showing the [frameWidth] x
     * [frameHeight] frame fitted inside it (`object-fit: contain`).
     */
    fun fit(frameWidth: Int, frameHeight: Int, viewWidth: Float, viewHeight: Float): BoxRect {
        if (frameWidth <= 0 || frameHeight <= 0) return BoxRect(0f, 0f, 0f, 0f)
        val scale = minOf(viewWidth / frameWidth, viewHeight / frameHeight)
        val left = (viewWidth - frameWidth * scale) / 2
        val top = (viewHeight - frameHeight * scale) / 2
        return BoxRect(left + x1 * scale, top + y1 * scale, (x2 - x1) * scale, (y2 - y1) * scale)
    }
}

data class BoxRect(val left: Float, val top: Float, val width: Float, val height: Float)

@Serializable
data class Detections(val width: Int = 0, val height: Int = 0, val boxes: List<DetectionBox> = emptyList())

/** A text message of an fMP4 stream. */
sealed interface LiveText {
    /** Announces the binary init segment that follows. */
    data class Init(val mime: String) : LiveText

    data class Boxes(val detections: Detections) : LiveText

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** `null` for unknown types or malformed JSON: newer servers may send more. */
        fun parse(text: String): LiveText? = runCatching {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "init" -> obj["mime"]?.jsonPrimitive?.contentOrNull?.let { Init(it) }
                "detections" -> Boxes(json.decodeFromJsonElement(Detections.serializer(), obj))
                else -> null
            }
        }.getOrNull()
    }
}
