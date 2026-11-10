package dev.minicam.mobile.data

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The device's dashboard status as last read on the home network, and when. */
@Serializable
data class SavedCentralStatus(
    val status: CentralStatus,
    @SerialName("saved_at") val savedAt: String,
)

/**
 * Keeps the last [CentralStatus] read from the device, because minicam answers it only on the
 * LAN: away from home "Connect to dashboard" shows this copy instead of an error. Holds no
 * secret (address, site, last sync); cleared on logout.
 */
class CentralStatusCache(
    private val store: CredentialStore,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(status: CentralStatus) {
        // The pairing answer's tunnel progress is a one-off; keep only the lasting status.
        val saved = SavedCentralStatus(status.copy(tunnel = null), now().toString())
        store.set(CredentialKey.CENTRAL_STATUS, json.encodeToString(SavedCentralStatus.serializer(), saved))
    }

    fun load(): SavedCentralStatus? = store.get(CredentialKey.CENTRAL_STATUS)?.let {
        runCatching { json.decodeFromString(SavedCentralStatus.serializer(), it) }.getOrNull()
    }

    fun clear() = store.set(CredentialKey.CENTRAL_STATUS, null)
}
