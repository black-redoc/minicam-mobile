package dev.minicam.mobile.data

import java.net.URI
import java.net.URLDecoder

// "Connect to dashboard" (docs/adr/0011): one dashboard code, typed or scanned as a QR, pairs the
// device and brings up its remote access (minicam ADR 0010, minicam-admin ADR 0012).

/** `minicam://pair?url=<dashboard origin>&code=<code>`, the QR on the dashboard's code page. */
data class PairLink(val url: String, val code: String) {
    override fun toString() = "PairLink(url=$url)"

    companion object {
        /** The link's dashboard address and code, both validated; null for anything else. */
        fun parse(link: String): PairLink? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "minicam" || uri.host != "pair") return null
            if (!uri.path.isNullOrEmpty() || uri.fragment != null || uri.userInfo != null) return null
            val params = (uri.rawQuery ?: return null).split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size != 2) return null
                runCatching { URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
            }
            if (params.size != params.map { it.first }.toSet().size) return null
            val values = params.toMap()
            if (values.keys != setOf("url", "code")) return null
            val url = values.getValue("url").takeIf { it.startsWith("https://") }?.let(CentralUrl::normalize) ?: return null
            val code = PairingCode.normalize(values.getValue("code")) ?: return null
            return PairLink(url, code)
        }
    }
}

/**
 * A link opened while the app could not use it (not signed in as an admin on the LAN). Kept in
 * memory only, for 10 minutes like the code itself, then dropped.
 */
class PendingPairLink(private val now: () -> Long = System::currentTimeMillis) {
    private var link: PairLink? = null
    private var storedAt = 0L

    fun store(value: PairLink) {
        link = value
        storedAt = now()
    }

    /** The link if still fresh; reading it clears it. */
    fun take(): PairLink? {
        val value = link?.takeIf { now() - storedAt < TTL_MILLIS }
        clear()
        return value
    }

    fun peek(): PairLink? = link?.takeIf { now() - storedAt < TTL_MILLIS }

    fun clear() {
        link = null
    }

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}

/** One progress row of the Connect screen. */
enum class StepState { WAITING, WORKING, DONE, SKIPPED, FAILED }

data class ConnectProgress(
    val dashboard: StepState = StepState.WAITING,
    val remoteAccess: StepState = StepState.WAITING,
    val dashboardText: String? = null,
    val remoteAccessText: String? = null,
    /** `https://<hostname>` once the tunnel runs. */
    val wanUrl: String? = null,
)

/** What the Remote access row shows for the pairing answer, before any polling. */
fun ConnectProgress.afterPairing(status: CentralStatus): ConnectProgress {
    val site = status.site?.let { "${it.name} (${it.hostname})" }
    val paired = copy(dashboard = StepState.DONE, dashboardText = site?.let { "Connected as $it" } ?: "Connected")
    val tunnel = status.tunnel
        ?: return paired.copy(remoteAccess = StepState.SKIPPED, remoteAccessText = "This dashboard does not set up remote access.")
    return when (tunnel.state) {
        "installing" -> paired.copy(
            remoteAccess = StepState.WORKING,
            remoteAccessText = "Setting up https://${tunnel.hostname}… (about a minute)" +
                if (tunnel.replaced) " It replaces the remote access set up from this app." else "",
        )
        "skipped" -> paired.copy(remoteAccess = StepState.SKIPPED, remoteAccessText = tunnel.message)
        else -> paired.copy(remoteAccess = StepState.FAILED, remoteAccessText = tunnel.message ?: "Remote access was not set up.")
    }
}

/** The Remote access row from the device's tunnel status while polling. */
fun ConnectProgress.withTunnel(status: TunnelStatus): ConnectProgress = when (status.state) {
    TunnelState.RUNNING -> copy(
        remoteAccess = StepState.DONE,
        remoteAccessText = "Ready at https://${status.hostname}",
        wanUrl = status.hostname?.let { "https://$it" },
    )
    TunnelState.ERROR -> copy(remoteAccess = StepState.FAILED, remoteAccessText = status.message ?: "The tunnel did not start.")
    else -> copy(remoteAccess = StepState.WORKING)
}

/**
 * Pairs with the dashboard and, when it handed over remote access, waits for the tunnel.
 * [onProgress] gets every change; the returned value is the final state.
 */
suspend fun MinicamApi.connectToDashboard(
    url: String,
    code: String,
    onProgress: (ConnectProgress) -> Unit = {},
    wait: suspend MinicamApi.() -> TunnelStatus = { waitForTunnel() },
): ConnectProgress {
    var progress = ConnectProgress(dashboard = StepState.WORKING)
    onProgress(progress)
    progress = progress.afterPairing(pairWithCentral(url, code))
    onProgress(progress)
    if (progress.remoteAccess == StepState.WORKING) {
        progress = progress.withTunnel(wait())
        onProgress(progress)
    }
    return progress
}
