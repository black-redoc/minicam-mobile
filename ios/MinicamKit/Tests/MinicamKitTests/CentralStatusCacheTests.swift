import Foundation
import Testing
@testable import MinicamKit

/// "Connect to dashboard" away from home shows the status last read on the LAN.
@Suite struct CentralStatusCacheTests {
    let store = InMemoryCredentialStore()
    let checked = Date(timeIntervalSince1970: 1_794_339_000)
    var cache: CentralStatusCache {
        let checked = checked
        return CentralStatusCache(store: store, now: { checked })
    }

    let connected = CentralStatus(
        connected: true, url: "https://admin.example.com", source: "app",
        lastSyncedAt: "2026-11-10T19:00:00Z", site: PairedSite(name: "Home", hostname: "cam.example.com"),
        tunnel: CentralTunnel(state: "installing", hostname: "cam.example.com")
    )

    @Test func centralStatusCache_keepsTheLastStatusWithoutTunnelProgress() throws {
        #expect(cache.load() == nil)

        cache.save(connected)

        let saved = try #require(cache.load())
        #expect(saved.status == CentralStatus(
            connected: true, url: "https://admin.example.com", source: "app",
            lastSyncedAt: "2026-11-10T19:00:00Z", site: PairedSite(name: "Home", hostname: "cam.example.com")
        ))
        #expect(saved.savedAt == checked)
    }

    @Test func centralStatusCache_replacedAndCleared() throws {
        cache.save(connected)
        cache.save(CentralStatus(connected: false))
        #expect(try #require(cache.load()).status.connected == false)

        cache.clear()
        #expect(cache.load() == nil)
        #expect(store.value(for: .centralStatus) == nil)
    }

    @Test func centralStatusCache_ignoresUnreadableData() {
        store.set("not json", for: .centralStatus)
        #expect(cache.load() == nil)
    }
}
