package dev.minicam.mobile.data

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// minicam's Cloudflare Tunnel status (minicam ADR 0008). Only the dashboard sets the tunnel
// up (docs/adr/0014); the device installs cloudflared itself and reports progress here.

@Serializable
enum class TunnelState {
    @SerialName("none") NONE,
    @SerialName("installing") INSTALLING,
    @SerialName("running") RUNNING,
    @SerialName("error") ERROR,
}

@Serializable
data class TunnelStatus(
    val available: Boolean,
    val state: TunnelState,
    val hostname: String? = null,
    val message: String? = null,
    /** "app" (set up by an older app) or "central" (by the dashboard; minicam ADR 0010). */
    val source: String? = null,
)

/** [MinicamException.NotFound] when this installation has no tunnel helper. */
suspend fun MinicamApi.tunnelStatus(): TunnelStatus = get("/api/system/tunnel", TunnelStatus.serializer())

/** Polls until the device reports running or error (installing takes about a minute). */
suspend fun MinicamApi.waitForTunnel(
    intervalMillis: Long = 2_000,
    timeoutMillis: Long = 300_000,
    sleep: suspend (Long) -> Unit = { delay(it) },
): TunnelStatus {
    var waited = 0L
    while (true) {
        val status = tunnelStatus()
        if (status.state == TunnelState.RUNNING || status.state == TunnelState.ERROR || waited >= timeoutMillis) {
            return status
        }
        sleep(intervalMillis)
        waited += intervalMillis
    }
}
