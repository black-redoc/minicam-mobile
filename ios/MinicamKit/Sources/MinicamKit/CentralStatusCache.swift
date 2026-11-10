import Foundation

/// The device's dashboard status as last read on the home network, and when.
public struct SavedCentralStatus: Codable, Equatable, Sendable {
    public let status: CentralStatus
    public let savedAt: Date

    enum CodingKeys: String, CodingKey { case status, savedAt = "saved_at" }
}

/// Keeps the last `CentralStatus` read from the device, because minicam answers it only on the
/// LAN: away from home "Connect to dashboard" shows this copy instead of an error. Holds no
/// secret (address, site, last sync); cleared on logout.
public struct CentralStatusCache: Sendable {
    private let store: CredentialStore
    private let now: @Sendable () -> Date

    public init(store: CredentialStore, now: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.now = now
    }

    public func save(_ status: CentralStatus) {
        // The pairing answer's tunnel progress is a one-off; keep only the lasting status.
        let lasting = CentralStatus(
            connected: status.connected, url: status.url, source: status.source,
            lastSyncedAt: status.lastSyncedAt, site: status.site
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(SavedCentralStatus(status: lasting, savedAt: now())) else { return }
        store.set(String(decoding: data, as: UTF8.self), for: .centralStatus)
    }

    public func load() -> SavedCentralStatus? {
        guard let text = store.value(for: .centralStatus) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(SavedCentralStatus.self, from: Data(text.utf8))
    }

    public func clear() {
        store.set(nil, for: .centralStatus)
    }
}
