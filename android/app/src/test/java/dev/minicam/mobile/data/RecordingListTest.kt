package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/specs/019 AC4/AC5: infinite scroll and new clips on top. */
class RecordingListTest {
    private fun clip(id: Int) = Recording(id, "cam", "2026-11-18T12:00:00+00:00", 10.0, 1)

    @Test
    fun loadMore_nearTheEnd() {
        assertTrue(shouldLoadMore(scrolled = 1_500, maxScroll = 2_000, hasMore = true, loading = false))
        assertFalse(shouldLoadMore(scrolled = 100, maxScroll = 2_000, hasMore = true, loading = false))
    }

    @Test
    fun loadMore_aShortListAfterADeleteFillsItself() {
        // Nothing to scroll: the list is shorter than the screen.
        assertTrue(shouldLoadMore(scrolled = 0, maxScroll = 0, hasMore = true, loading = false))
    }

    @Test
    fun loadMore_notWithoutMorePagesOrWhileLoading() {
        assertFalse(shouldLoadMore(0, 0, hasMore = false, loading = false))
        assertFalse(shouldLoadMore(0, 0, hasMore = true, loading = true))
    }

    @Test
    fun merge_newClipsGoOnTop() {
        val current = listOf(clip(5), clip(4))

        assertEquals(listOf(7, 6, 5, 4), mergeNewest(current, listOf(clip(7), clip(6), clip(5)))?.map { it.id })
    }

    @Test
    fun merge_nothingNewKeepsTheSameList() {
        val current = listOf(clip(5), clip(4))

        assertSame(current, mergeNewest(current, listOf(clip(5), clip(4))))
    }

    @Test
    fun merge_deletedClipsDoNotComeBack() {
        // Clip 4 was deleted here; the newest page still lists 5 and older only.
        val current = listOf(clip(5), clip(3))

        assertSame(current, mergeNewest(current, listOf(clip(5), clip(3), clip(2))))
    }

    @Test
    fun merge_aFullPageOfNewClipsResetsTheList() {
        assertNull(mergeNewest(listOf(clip(1)), listOf(clip(4), clip(3), clip(2)), pageSize = 3))
    }

    @Test
    fun merge_intoAnEmptyList() {
        assertEquals(listOf(2, 1), mergeNewest(emptyList(), listOf(clip(2), clip(1)), pageSize = 3)?.map { it.id })
        assertNull(mergeNewest(emptyList(), listOf(clip(3), clip(2), clip(1)), pageSize = 3))
    }
}
