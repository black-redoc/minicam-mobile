package dev.minicam.mobile.data

/**
 * Digital zoom of a video view (spec 008): the picture is scaled around its centre and panned.
 * The pan is a fraction of the view's size, so it survives switching between layouts, and is
 * kept within `±(scale - 1) / 2` so the picture never shows past its edges.
 */
data class VideoZoom(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val canZoomIn: Boolean get() = scale < STEPS.last()
    val canZoomOut: Boolean get() = scale > STEPS.first()
    val isZoomed: Boolean get() = scale > STEPS.first()

    fun zoomIn(): VideoZoom = copy(scale = STEPS.firstOrNull { it > scale } ?: STEPS.last()).clamped()

    fun zoomOut(): VideoZoom = copy(scale = STEPS.lastOrNull { it < scale } ?: STEPS.first()).clamped()

    /** Moves the picture by a fraction of the view's width ([dx]) and height ([dy]). */
    fun panBy(dx: Float, dy: Float): VideoZoom = copy(panX = panX + dx, panY = panY + dy).clamped()

    private fun clamped(): VideoZoom {
        val limit = (scale - 1f) / 2f
        // At 1× the bounds are ±0, and coerceIn would hand back -0f for a negative pan.
        if (limit == 0f) return copy(panX = 0f, panY = 0f)
        return copy(panX = panX.coerceIn(-limit, limit), panY = panY.coerceIn(-limit, limit))
    }

    companion object {
        val STEPS = listOf(1f, 1.5f, 2f, 3f, 4f)
    }
}
