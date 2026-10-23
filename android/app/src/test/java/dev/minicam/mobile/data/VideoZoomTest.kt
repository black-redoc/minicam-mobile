package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoZoomTest {
    @Test
    fun videoZoom_stepsBetweenLimits() {
        var zoom = VideoZoom()
        assertFalse(zoom.canZoomOut)
        val scales = mutableListOf(zoom.scale)
        while (zoom.canZoomIn) {
            zoom = zoom.zoomIn()
            scales += zoom.scale
        }
        assertEquals(listOf(1f, 1.5f, 2f, 3f, 4f), scales)
        assertEquals(4f, zoom.zoomIn().scale)
        assertTrue(zoom.canZoomOut)
        assertEquals(3f, zoom.zoomOut().scale)
        assertEquals(1f, VideoZoom().zoomOut().scale)
    }

    @Test
    fun videoZoom_panIsClamped() {
        // Not zoomed: nothing to pan.
        assertEquals(VideoZoom(), VideoZoom().panBy(0.3f, -0.3f))

        // 3×: the picture may move a whole view width either way, no further.
        val zoomed = VideoZoom(scale = 3f).panBy(5f, -0.4f)
        assertEquals(1f, zoomed.panX)
        assertEquals(-0.4f, zoomed.panY)

        // Zooming out to 2× pulls the pan back inside ±0.5.
        val out = zoomed.zoomOut()
        assertEquals(2f, out.scale)
        assertEquals(0.5f, out.panX)
        assertEquals(-0.4f, out.panY)
        assertEquals(0f, out.zoomOut().zoomOut().panX)
    }
}
