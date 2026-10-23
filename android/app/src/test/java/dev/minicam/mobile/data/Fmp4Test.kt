package dev.minicam.mobile.data

import dev.minicam.mobile.data.Fmp4Fixtures.Traf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Fmp4Test {
    @Test
    fun fmp4_parsesInitSegment() {
        assertEquals(
            listOf(Fmp4.Track(1, Fmp4.Kind.VIDEO, 90_000), Fmp4.Track(2, Fmp4.Kind.AUDIO, 16_000)),
            Fmp4.parseInit(Fmp4Fixtures.init()),
        )
    }

    @Test
    fun fmp4_parsesFragment() {
        val fragment = Fmp4Fixtures.fragment(Traf(1, 900_000, listOf(10)), Traf(2, 160_000, listOf(4, 5), sync = true))

        val trafs = Fmp4.parseFragment(fragment)

        assertEquals(listOf(1, 2), trafs.map { it.trackId })
        assertEquals(listOf(900_000L, 160_000L), trafs.map { it.baseDecodeTime })
        assertEquals(listOf(Fmp4.Sample(3_000, 10, isSync = true, compositionOffset = 0)), trafs[0].samples)
        // Only the first sample takes first-sample-flags; the rest use tfhd's non-sync default.
        assertEquals(listOf(true to 4, false to 5), trafs[1].samples.map { it.isSync to it.size })
        val nonKey = Fmp4.parseFragment(Fmp4Fixtures.fragment(Traf(1, 0, listOf(3), sync = false)))
        assertEquals(false, nonKey.single().samples.single().isSync)
    }

    @Test
    fun fmp4_rejectsMalformedBoxes() {
        val fragment = Fmp4Fixtures.fragment(Traf(1, 0, listOf(10)))
        // Truncated, a box claiming more bytes than there are, and a sample count out of range.
        assertThrows(Fmp4Exception::class.java) { Fmp4.parseFragment(fragment.copyOf(fragment.size - 3)) }
        val oversized = fragment.copyOf().also { Fmp4Fixtures.u32(0x7FFF_FFFF).copyInto(it, 0) }
        assertThrows(Fmp4Exception::class.java) { Fmp4.parseFragment(oversized) }
        assertThrows(Fmp4Exception::class.java) { Fmp4.parseInit(ByteArray(3)) }
        assertThrows(Fmp4Exception::class.java) { Fmp4.parseInit(Fmp4Fixtures.box("ftyp")) }
        val trun = Fmp4Fixtures.box(
            "moof",
            Fmp4Fixtures.box(
                "traf",
                Fmp4Fixtures.fullBox("tfhd", 0, 0, Fmp4Fixtures.u32(1)),
                Fmp4Fixtures.fullBox("tfdt", 0, 0, Fmp4Fixtures.u32(0)),
                Fmp4Fixtures.fullBox("trun", 0, 0x200, Fmp4Fixtures.u32(0xFFFF_FFFFL)),
            ),
        )
        assertThrows(Fmp4Exception::class.java) { Fmp4.parseFragment(trun) }
    }

    @Test
    fun fmp4_rebasesDecodeTimes() {
        val rebaser = Fmp4Rebaser(Fmp4Fixtures.init())

        // The first fragment sets the origin (10 s); later ones move back by the same time.
        val first = rebaser.rebase(Fmp4Fixtures.fragment(Traf(1, 900_000, listOf(4))))
        val second = rebaser.rebase(Fmp4Fixtures.fragment(Traf(1, 990_000, listOf(4)), Traf(2, 176_000, listOf(2), tfdtVersion = 0)))

        assertEquals(listOf(0L), Fmp4.parseFragment(first).map { it.baseDecodeTime })
        assertEquals(listOf(90_000L, 16_000L), Fmp4.parseFragment(second).map { it.baseDecodeTime })
        // Only tfdt changes: same size, same samples.
        val original = Fmp4Fixtures.fragment(Traf(1, 990_000, listOf(4)), Traf(2, 176_000, listOf(2), tfdtVersion = 0))
        assertEquals(original.size, second.size)
        assertEquals(original.takeLast(6), second.takeLast(6))
    }
}
