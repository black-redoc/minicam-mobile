package dev.minicam.mobile.data

import kotlinx.serialization.Serializable

// Detection boxes of a recorded clip (ADR 0016, minicam ADR 0012): minicam saves the boxes it
// saw while recording next to the clip, timed from its start, and the player draws them.

/** One moment of a clip: these boxes replace the ones before; empty clears them. */
@Serializable
data class DetectionFrame(val t: Double, val boxes: List<DetectionBox> = emptyList())

/** `GET /api/recordings/{id}/detections`; boxes in [width] x [height] frame pixels. */
@Serializable
data class DetectionTrack(
    val version: Int = 1,
    val width: Int = 0,
    val height: Int = 0,
    val frames: List<DetectionFrame> = emptyList(),
) {
    /**
     * The boxes to draw at [positionSeconds]: the last frame at or before it, unless it is older
     * than [HOLD_SECONDS] (a stalled detector must not leave boxes on screen). Null for none.
     */
    fun at(positionSeconds: Double): Detections? {
        val frame = frames.lastOrNull { it.t <= positionSeconds } ?: return null
        if (frame.boxes.isEmpty() || positionSeconds - frame.t > HOLD_SECONDS) return null
        return Detections(width, height, frame.boxes)
    }

    companion object {
        const val HOLD_SECONDS = 3.0
        val EMPTY = DetectionTrack()
    }
}

/** The clip's boxes; none for a server that predates them (404). */
suspend fun MinicamApi.recordingDetections(id: Int): DetectionTrack = try {
    get("/api/recordings/$id/detections", DetectionTrack.serializer())
} catch (_: MinicamException.NotFound) {
    DetectionTrack.EMPTY
}
