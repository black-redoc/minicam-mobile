package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStreamTest {
    private val hevc = "video/mp4; codecs=\"hvc1.1.6.L90\""
    private val hevcAac = "video/mp4; codecs=\"hvc1.1.6.L90,mp4a.40.2\""

    @Test
    fun streamFormat_fallsBackToMjpeg() {
        val token = StreamToken("t", 30, hevc, hevcAac)
        val everything: (List<String>) -> Boolean = { true }
        val noHevc: (List<String>) -> Boolean = { "video/hevc" !in it }

        assertEquals(StreamChoice(StreamFormat.FMP4, false, hevc), chooseStream(token, true, false, everything))
        assertEquals(StreamChoice(StreamFormat.FMP4, true, hevcAac), chooseStream(token, true, true, everything))
        // Codec not decodable, codec unknown yet, or fMP4 failed before: MJPEG, never audio.
        val mjpeg = StreamChoice(StreamFormat.MJPEG, false, null)
        assertEquals(mjpeg, chooseStream(token, true, true, noHevc))
        assertEquals(mjpeg, chooseStream(StreamToken("t", 30), true, true, everything))
        assertEquals(mjpeg, chooseStream(token, false, true, everything))
        // Audio asked for but not offered (no can_listen): video only.
        assertEquals(StreamChoice(StreamFormat.FMP4, false, hevc), chooseStream(StreamToken("t", 30, hevc), true, true, everything))
        assertEquals(listOf("video/hevc", "audio/mp4a-latm"), LiveCodecs.sampleMimes(hevcAac))
        assertEquals(listOf("video/avc"), LiveCodecs.sampleMimes("video/mp4; codecs=\"avc1.64001f\""))
        assertNull(LiveCodecs.sampleMimes("video/mp4; codecs=\"vp09.00.10.08\""))
        assertNull(LiveCodecs.sampleMimes("video/mp4"))
    }

    @Test
    fun liveCatchUp_speedsUpWhenBehind() {
        assertEquals(LiveCatchUp.FAST, LiveCatchUp.speed(1_500, 1f))
        // Between the thresholds the speed holds, so it doesn't flap.
        assertEquals(LiveCatchUp.FAST, LiveCatchUp.speed(600, LiveCatchUp.FAST))
        assertEquals(1f, LiveCatchUp.speed(600, 1f))
        assertEquals(1f, LiveCatchUp.speed(200, LiveCatchUp.FAST))
    }

    @Test
    fun detections_parseMessage() {
        val message = """{"type": "detections", "width": 1920, "height": 1080,
            "boxes": [{"label": "person", "confidence": 0.87, "color": "#ff0000", "x1": 10, "y1": 20, "x2": 110, "y2": 220}]}"""

        val parsed = LiveText.parse(message)

        assertEquals(
            LiveText.Boxes(Detections(1920, 1080, listOf(DetectionBox("person", 0.87f, "#ff0000", 10f, 20f, 110f, 220f)))),
            parsed,
        )
        assertEquals(LiveText.Init(hevc), LiveText.parse("""{"type": "init", "mime": "video/mp4; codecs=\"hvc1.1.6.L90\""}"""))
        assertNull(LiveText.parse("""{"type": "stats"}"""))
        assertNull(LiveText.parse("not json"))
    }

    @Test
    fun detectionBox_fitsContainedFrame() {
        val box = DetectionBox("car", 0.5f, "#00ff00", 960f, 0f, 1920f, 540f)

        // 1920x1080 in a 4:3 view: full width, letterboxed top and bottom by 75 px each.
        assertEquals(BoxRect(400f, 75f, 400f, 225f), box.fit(1920, 1080, 800f, 600f))
        // Same aspect: plain scaling.
        assertEquals(BoxRect(80f, 0f, 80f, 45f), box.fit(1920, 1080, 160f, 90f))
        assertEquals(BoxRect(0f, 0f, 0f, 0f), box.fit(0, 0, 160f, 90f))
    }
}
