package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/specs/019 AC2: the boxes drawn at a playback position. */
class RecordingDetectionsTest {
    private val person = DetectionBox("person", 0.9f, "#ff0000", 1f, 2f, 3f, 4f)
    private val dog = DetectionBox("dog", 0.8f, "#00c800", 5f, 6f, 7f, 8f)
    private val track = DetectionTrack(
        width = 640,
        height = 360,
        frames = listOf(
            DetectionFrame(1.0, listOf(person)),
            DetectionFrame(2.0, listOf(dog)),
            DetectionFrame(4.0, emptyList()),
            DetectionFrame(10.0, listOf(person)),
        ),
    )

    @Test
    fun at_nothingBeforeTheFirstFrame() {
        assertNull(track.at(0.5))
    }

    @Test
    fun at_theLastFrameAtOrBeforeThePosition() {
        assertEquals(Detections(640, 360, listOf(person)), track.at(1.0))
        assertEquals(Detections(640, 360, listOf(dog)), track.at(3.9))
    }

    @Test
    fun at_anEmptyFrameClearsTheBoxes() {
        assertNull(track.at(5.0))
    }

    @Test
    fun at_boxesHoldAtMostThreeSeconds() {
        assertEquals(Detections(640, 360, listOf(person)), track.at(13.0))
        assertNull(track.at(13.1))
    }

    @Test
    fun at_anEmptyTrackHasNoBoxes() {
        assertNull(DetectionTrack.EMPTY.at(1.0))
    }
}
