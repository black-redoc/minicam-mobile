package dev.minicam.mobile.push

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detection channel's shotgun sound and vibration (docs/adr/0007). */
class DetectionSoundTest {
    @Test
    fun detectionSound_isSmallMp3() {
        // Unit tests run from the module directory.
        val bytes = File("src/main/res/raw/detection_shotgun.mp3").readBytes()
        val id3 = bytes.size > 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "ID3"
        val frameSync = bytes.size > 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
        assertTrue("starts with an ID3 tag or an MPEG frame", id3 || frameSync)
        assertTrue("${bytes.size} bytes", bytes.size in 10_000..300_000)
    }

    @Test
    fun detectionVibration_followsTheReloadAndTheShot() {
        val pattern = DetectionNotifications.VIBRATION_PATTERN
        assertEquals("three buzzes", 6, pattern.size)
        assertTrue("starts right away", pattern[0] <= 50)
        assertTrue("fits the audible part of the sound", pattern.sum() <= 1_200)
    }
}
