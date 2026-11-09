package dev.minicam.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Connection to the central dashboard (minicam ADR 0009, minicam-admin ADR 0011): the admin
// types the dashboard address and the one-time code it shows; minicam exchanges the code for
// its device token itself, so the token never reaches the phone. Admins only, LAN only.

@Serializable
data class PairedSite(val name: String, val hostname: String)

/**
 * Remote access the dashboard handed over when pairing (minicam ADR 0010): `installing` (the
 * device is setting up its tunnel; poll [tunnelStatus]), `skipped` (Access is set in the
 * device's .env) or `error`. Null when the dashboard provisions no remote access.
 */
@Serializable
data class CentralTunnel(
    val state: String,
    val hostname: String? = null,
    val message: String? = null,
    /** A tunnel set up from this app was replaced by the dashboard's. */
    val replaced: Boolean = false,
)

@Serializable
data class CentralStatus(
    val connected: Boolean,
    val url: String? = null,
    /** "env": set in the device's .env (read only here); "app": paired from the app. */
    val source: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    val site: PairedSite? = null,
    /** Only in the answer to [pairWithCentral]. */
    val tunnel: CentralTunnel? = null,
) {
    val managedInEnv: Boolean get() = source == "env"
}

@Serializable
internal data class CentralPairRequest(
    val url: String,
    @SerialName("pairing_code") val pairingCode: String,
) {
    override fun toString() = "CentralPairRequest(url=$url)"
}

object PairingCode {
    private val PATTERN = Regex("[A-Za-z0-9]{8}")

    /** What the dashboard shows (`ABCD-EFGH`), ignoring dashes and spaces. */
    fun normalize(input: String): String? =
        input.filterNot { it == '-' || it.isWhitespace() }.uppercase().takeIf { PATTERN.matches(it) }

    /** What the field keeps while typing: up to 8 uppercase letters and digits, nothing else. */
    fun clean(input: String): String = input.filter { it.code < 128 && it.isLetterOrDigit() }.uppercase().take(8)

    /** [clean] with the dash the dashboard shows inserted once a fifth character is typed. */
    fun format(input: String): String = clean(input).let { if (it.length > 4) "${it.take(4)}-${it.drop(4)}" else it }
}

object CentralUrl {
    /** `https://host` with no path, as minicam requires; null otherwise. */
    fun normalize(input: String): String? {
        val trimmed = input.trim().removeSuffix("/")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        if (url.scheme != "https" || url.host.isNullOrEmpty()) return null
        if (!url.path.isNullOrEmpty() || url.query != null || url.userInfo != null) return null
        val port = if (url.port == -1) "" else ":${url.port}"
        return "https://${url.host.lowercase()}$port"
    }
}

suspend fun MinicamApi.centralStatus(): CentralStatus = get("/api/system/central", CentralStatus.serializer())

/** Pairs (or re-pairs, which rotates the device token) with the dashboard at [url]. */
suspend fun MinicamApi.pairWithCentral(url: String, pairingCode: String): CentralStatus = sendJson(
    "POST",
    "/api/system/central",
    CentralPairRequest.serializer(),
    CentralPairRequest(url, pairingCode),
    CentralStatus.serializer(),
)

suspend fun MinicamApi.unpairFromCentral(): CentralStatus =
    delete("/api/system/central", CentralStatus.serializer())
