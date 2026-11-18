package dev.minicam.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Updating the device to a newer minicam release (docs/adr/0017, minicam ADR 0013). Admins only;
// the device downloads, verifies and installs the release itself.

/** `GET /api/system/update`. */
@Serializable
data class SystemUpdate(
    /** False on an installation without minicam's update helper (not from the image). */
    val available: Boolean = false,
    val current: String = "",
    val latest: String? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    val state: String = "idle",
    /** The release being (or last) installed. */
    val version: String? = null,
    val message: String? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
) {
    val busy: Boolean get() = state in BUSY_STATES

    companion object {
        val BUSY_STATES = setOf("requested", "downloading", "verifying", "installing", "restarting")
    }
}

@Serializable
internal data class UpdateRequest(val version: String)

/** The device's version and update; null from a minicam older than updates (404). */
suspend fun MinicamApi.systemUpdate(refresh: Boolean = false): SystemUpdate? = try {
    get("/api/system/update${if (refresh) "?refresh=true" else ""}", SystemUpdate.serializer())
} catch (_: MinicamException.NotFound) {
    null
}

/** Asks the device to install [version], the release `GET` offered. */
suspend fun MinicamApi.startUpdate(version: String): SystemUpdate = sendJson(
    "POST",
    "/api/system/update",
    UpdateRequest.serializer(),
    UpdateRequest(version),
    SystemUpdate.serializer(),
)

/** Where an update the admin started from this phone stands. */
sealed interface UpdateProgress {
    /** Working; [state] is the device's (`downloading` …), or `restarting` while it doesn't answer. */
    data class Running(val target: String, val state: String, val message: String?) : UpdateProgress

    data class Done(val version: String) : UpdateProgress

    data class Failed(val message: String?) : UpdateProgress
}

/**
 * Follows an update by polling: minicam stops answering while it restarts, and the update is
 * over once it answers with the new version, or reports an error.
 */
class UpdateTracker(val target: String, private val startedAtMillis: Long) {
    /** The progress after a successful poll. */
    fun onStatus(status: SystemUpdate, nowMillis: Long): UpdateProgress = when {
        status.current == target -> UpdateProgress.Done(target)
        status.state == "error" -> UpdateProgress.Failed(status.message)
        // "done" for another version, or idle again: the helper restored the old release.
        !status.busy && nowMillis - startedAtMillis > GRACE_MILLIS -> UpdateProgress.Failed(status.message)
        else -> timedOut(nowMillis) ?: UpdateProgress.Running(target, status.state, status.message)
    }

    /** The progress after a failed poll: expected while minicam restarts. */
    fun onUnreachable(nowMillis: Long): UpdateProgress =
        timedOut(nowMillis) ?: UpdateProgress.Running(target, "restarting", null)

    private fun timedOut(nowMillis: Long): UpdateProgress? =
        if (nowMillis - startedAtMillis > TIMEOUT_MILLIS) UpdateProgress.Failed(null) else null

    companion object {
        const val POLL_MILLIS = 3_000L

        /** Download, unpack and a 3-minute health check on a Pi, with room to spare. */
        const val TIMEOUT_MILLIS = 20 * 60_000L

        /** The helper may not have picked the request up yet right after it was sent. */
        const val GRACE_MILLIS = 15_000L
    }
}
