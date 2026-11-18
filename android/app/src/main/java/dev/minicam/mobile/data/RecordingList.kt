package dev.minicam.mobile.data

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The clip list as it changes while it is open (ADR 0016): infinite scroll, deletes and new
// clips announced by detection pushes.

/** Distance from the end of the list, in pixels, at which the next page is asked for. */
const val LOAD_MORE_THRESHOLD_PX = 600

/** True when the list is scrolled to within [thresholdPx] of its end and has more pages. */
fun shouldLoadMore(
    scrolled: Int,
    maxScroll: Int,
    hasMore: Boolean,
    loading: Boolean,
    thresholdPx: Int = LOAD_MORE_THRESHOLD_PX,
): Boolean = hasMore && !loading && maxScroll - scrolled <= thresholdPx

/**
 * [current] with the clips of [newest] (the first page, newest first) that it doesn't have yet
 * on top. Null when [newest] is a full page of new clips: there may be a gap below them, so the
 * list should be loaded again from the top.
 */
fun mergeNewest(current: List<Recording>, newest: List<Recording>, pageSize: Int = RECORDINGS_PAGE_SIZE): List<Recording>? {
    val top = current.maxOfOrNull { it.id } ?: return newest.takeIf { it.size < pageSize }
    val added = newest.filter { it.id > top }
    if (added.isEmpty()) return current
    if (added.size >= pageSize) return null
    return added + current
}

/** A detection pushed to this phone whose clip may not be listed yet. */
data class PendingClip(val camera: String, val detectedAt: Instant, val receivedAt: Instant) {
    /** The clip of this camera that runs over the detection (it starts a few seconds before). */
    fun coveredBy(recording: Recording): Boolean {
        if (recording.cameraName != camera) return false
        val start = recording.started.minus(SLACK)
        val end = recording.started.plusMillis((recording.durationSeconds * 1000).toLong()).plus(SLACK)
        return !detectedAt.isBefore(start) && !detectedAt.isAfter(end)
    }

    /** Recording off, a failed clip, or a camera hidden by the filter: stop waiting. */
    fun expired(now: Instant): Boolean = Duration.between(receivedAt, now) > MAX_WAIT

    companion object {
        /** Longest clip (5 min) plus post-roll and writing time. */
        val MAX_WAIT: Duration = Duration.ofMinutes(7)
        val SLACK: Duration = Duration.ofSeconds(10)
    }
}

/**
 * Detections from pushes, waiting for their clip. The push service and the Recordings screen
 * share the process, so this lives in memory only: a clip saved while the app was closed is
 * listed on the next visit anyway.
 */
object PendingClips {
    /** How often the Recordings screen asks for the newest clips while some are pending. */
    const val POLL_MILLIS = 5_000L

    private val state = MutableStateFlow<List<PendingClip>>(emptyList())
    val pending: StateFlow<List<PendingClip>> = state.asStateFlow()

    fun add(camera: String, detectedAt: String, now: Instant = Instant.now()) {
        val at = parseRecordingStart(detectedAt) ?: now
        state.update { list -> list.filterNot { it.expired(now) } + PendingClip(camera, at, now) }
    }

    /** Drops the detections [recordings] cover, and the expired ones. */
    fun resolve(recordings: List<Recording>, now: Instant = Instant.now()) {
        state.update { list ->
            list.filterNot { pending -> pending.expired(now) || recordings.any(pending::coveredBy) }
        }
    }

    fun clear() = state.update { emptyList() }
}
