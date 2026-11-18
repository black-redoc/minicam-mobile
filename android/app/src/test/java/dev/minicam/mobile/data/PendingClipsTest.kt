package dev.minicam.mobile.data

import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/specs/019 AC6: waiting for the clip of a detection push. */
class PendingClipsTest {
    private val now: Instant = Instant.parse("2026-11-18T12:00:00Z")

    private fun clip(camera: String, start: String, seconds: Double) = Recording(1, camera, start, seconds, 1)

    @After
    fun tearDown() = PendingClips.clear()

    @Test
    fun coveredBy_aClipOfItsCameraAroundTheDetection() {
        val pending = PendingClip("door", Instant.parse("2026-11-18T12:00:05Z"), now)

        // The clip starts 3 s before the detection (pre-roll).
        assertTrue(pending.coveredBy(clip("door", "2026-11-18T12:00:02+00:00", 20.0)))
        assertFalse(pending.coveredBy(clip("garage", "2026-11-18T12:00:02+00:00", 20.0)))
        // An earlier clip of the same camera, long finished.
        assertFalse(pending.coveredBy(clip("door", "2026-11-18T11:50:00+00:00", 20.0)))
        // A later clip, after a gap.
        assertFalse(pending.coveredBy(clip("door", "2026-11-18T12:01:00+00:00", 20.0)))
    }

    @Test
    fun expired_afterSevenMinutes() {
        val pending = PendingClip("door", now, now)

        assertFalse(pending.expired(now.plusSeconds(7 * 60)))
        assertTrue(pending.expired(now.plusSeconds(7 * 60 + 1)))
    }

    @Test
    fun add_usesTheDetectionTimeOrNow() {
        PendingClips.add("door", "2026-11-18T11:59:58+00:00", now)
        PendingClips.add("garage", "not a date", now)

        assertEquals(
            listOf(
                PendingClip("door", Instant.parse("2026-11-18T11:59:58Z"), now),
                PendingClip("garage", now, now),
            ),
            PendingClips.pending.value,
        )
    }

    @Test
    fun resolve_dropsCoveredAndExpiredDetections() {
        PendingClips.add("door", "2026-11-18T11:50:00+00:00", now.minusSeconds(8 * 60))
        PendingClips.add("door", "2026-11-18T12:00:00+00:00", now)
        PendingClips.add("garage", "2026-11-18T12:00:00+00:00", now)

        PendingClips.resolve(listOf(clip("door", "2026-11-18T11:59:57+00:00", 12.0)), now)

        assertEquals(listOf("garage"), PendingClips.pending.value.map { it.camera })
    }
}
