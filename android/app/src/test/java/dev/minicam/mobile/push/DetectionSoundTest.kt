package dev.minicam.mobile.push

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detection channel's reload sound and vibration (docs/adr/0007). */
class DetectionSoundTest {
    @Test
    fun detectionSound_isShortMonoWav() {
        // Unit tests run from the module directory.
        val file = File("src/main/res/raw/detection_reload.wav")
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            assertEquals(1, format.channels)
            assertEquals(16, format.sampleSizeInBits)
            val seconds = stream.frameLength / format.frameRate
            assertTrue("sound lasts $seconds s", seconds in 0.2f..2f)
        }
    }

    @Test
    fun detectionVibration_buzzesTwiceWithinTheSound() {
        val pattern = DetectionNotifications.VIBRATION_PATTERN
        assertEquals("starts right away, then two buzzes", listOf(0L), pattern.take(1))
        assertEquals(4, pattern.size)
        assertTrue("fits the 0.75 s sound", pattern.sum() <= 750)
    }
}
