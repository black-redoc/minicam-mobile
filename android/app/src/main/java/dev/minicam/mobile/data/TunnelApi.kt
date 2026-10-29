package dev.minicam.mobile.data

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// minicam's Cloudflare Tunnel endpoints (minicam ADR 0008, docs/adr/0009):
// admins only, LAN only. The device installs cloudflared itself and reports
// progress through tunnelStatus().

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
)

/** What the device needs from Cloudflare; the API token never leaves the app. */
@Serializable
data class TunnelSetup(
    @SerialName("tunnel_token") val tunnelToken: String,
    val hostname: String,
    @SerialName("team_domain") val teamDomain: String,
    val aud: String,
) {
    // The tunnel token is a credential: keep it out of logs and crash reports.
    override fun toString() = "TunnelSetup(hostname=$hostname, teamDomain=$teamDomain)"
}

/** [MinicamException.NotFound] when this installation has no tunnel helper. */
suspend fun MinicamApi.tunnelStatus(): TunnelStatus = get("/api/system/tunnel", TunnelStatus.serializer())

suspend fun MinicamApi.setUpTunnel(setup: TunnelSetup) {
    sendJsonNoContent("POST", "/api/system/tunnel", TunnelSetup.serializer(), setup)
}

suspend fun MinicamApi.removeTunnel() = sendEmpty("DELETE", "/api/system/tunnel")

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
