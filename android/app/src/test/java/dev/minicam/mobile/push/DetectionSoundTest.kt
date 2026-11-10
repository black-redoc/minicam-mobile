package dev.minicam.mobile.push

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detection channel's reload sound (docs/adr/0007): short, mono, 16-bit PCM. */
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
}
