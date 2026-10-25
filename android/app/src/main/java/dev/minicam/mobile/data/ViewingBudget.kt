package dev.minicam.mobile.data

/**
 * How long a camera's live view may stream per open (spec 011). Time counts only between
 * [start] and [stop], so pauses (background, reconnects) keep what was already used; once
 * [expired], the stream stays closed until the camera is opened again with a new budget.
 */
class ViewingBudget(
    private val limitMs: Long = LIMIT_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var usedMs = 0L
    private var runningSince: Long? = null

    val remainingMs: Long
        get() = (limitMs - usedMs - (runningSince?.let { clock() - it } ?: 0L)).coerceAtLeast(0L)

    val expired: Boolean get() = remainingMs == 0L

    fun start() {
        if (runningSince == null) runningSince = clock()
    }

    fun stop() {
        runningSince?.let { usedMs += clock() - it }
        runningSince = null
    }

    companion object {
        const val LIMIT_MS = 30_000L
    }
}
