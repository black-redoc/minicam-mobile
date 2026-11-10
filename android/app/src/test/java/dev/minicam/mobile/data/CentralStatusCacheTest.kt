package dev.minicam.mobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Connect to dashboard" away from home shows the status last read on the LAN. */
class CentralStatusCacheTest {
    private val store = InMemoryCredentialStore()
    private val cache = CentralStatusCache(store) { Instant.parse("2026-11-10T19:30:00Z") }

    private val connected = CentralStatus(
        connected = true,
        url = "https://admin.example.com",
        source = "app",
        lastSyncedAt = "2026-11-10T19:00:00Z",
        site = PairedSite("Home", "cam.example.com"),
        tunnel = CentralTunnel("installing", "cam.example.com"),
    )

    @Test
    fun centralStatusCache_keepsTheLastStatusWithoutTunnelProgress() {
        assertNull(cache.load())

        cache.save(connected)

        val saved = cache.load()!!
        assertEquals(connected.copy(tunnel = null), saved.status)
        assertEquals("2026-11-10T19:30:00Z", saved.savedAt)
    }

    @Test
    fun centralStatusCache_replacedAndCleared() {
        cache.save(connected)
        cache.save(CentralStatus(connected = false))
        assertEquals(false, cache.load()!!.status.connected)

        cache.clear()
        assertNull(cache.load())
        assertNull(store.get(CredentialKey.CENTRAL_STATUS))
    }

    @Test
    fun centralStatusCache_ignoresUnreadableData() {
        store.set(CredentialKey.CENTRAL_STATUS, "not json")
        assertNull(cache.load())
    }
}
