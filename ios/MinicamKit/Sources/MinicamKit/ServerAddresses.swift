import Foundation

/// Which address of the server the app talks to (docs/adr/0013).
public enum Network: String, Sendable, CaseIterable, Identifiable {
    case local = "LOCAL"
    case remote = "REMOTE"

    public var id: String { rawValue }
}

public extension ServerConfig {
    var network: Network { isWAN ? .remote : .local }
    var origin: String { baseURL.absoluteString }
}

/// The local (`http`, LAN host) and remote (`https`) address of one minicam, and which one is
/// active (docs/adr/0013). Both reach the same server, so switching keeps the session and the
/// Access token; an address outside the pair is another server and clears them.
public struct ServerAddresses: Sendable {
    private let store: CredentialStore

    public init(store: CredentialStore) {
        self.store = store
        // Before ADR 0013 the app kept a single `server_url`.
        if store.value(for: .lanURL) == nil, store.value(for: .wanURL) == nil,
           let saved = store.value(for: .serverURL), let config = try? ServerConfig(parsing: saved) {
            save(config, activate: true)
        }
    }

    public var local: ServerConfig? { parse(store.value(for: .lanURL)) }
    public var remote: ServerConfig? { parse(store.value(for: .wanURL)) }

    public var active: ServerConfig? {
        switch store.value(for: .activeNetwork).flatMap(Network.init(rawValue:)) {
        case .local: local
        case .remote: remote
        case nil: nil
        }
    }

    public subscript(network: Network) -> ServerConfig? { network == .local ? local : remote }

    /// Makes `config` the active address. Returns true when it is one of the saved pair (the
    /// session is kept); otherwise it is a new server and every credential is cleared.
    @discardableResult
    public func use(_ config: ServerConfig) -> Bool {
        if self[config.network] == config {
            save(config, activate: true)
            return true
        }
        store.clearSession()
        store.set(nil, for: .accessToken)
        store.set(nil, for: .lanURL)
        store.set(nil, for: .wanURL)
        save(config, activate: true)
        return false
    }

    /// Saves `config` as the other address of the active server, keeping every credential.
    public func add(_ config: ServerConfig) {
        save(config, activate: false)
    }

    /// Activates the saved `network` address and returns it; nil when it was never added.
    @discardableResult
    public func switchTo(_ network: Network) -> ServerConfig? {
        guard let config = self[network] else { return nil }
        save(config, activate: true)
        return config
    }

    /// Remote without an Access token must go through Cloudflare Access first.
    public func needsAccessLogin(_ config: ServerConfig) -> Bool {
        config.isWAN && store.value(for: .accessToken) == nil
    }

    private func save(_ config: ServerConfig, activate: Bool) {
        store.set(config.origin, for: config.network == .local ? .lanURL : .wanURL)
        if activate {
            store.set(config.network.rawValue, for: .activeNetwork)
            store.set(config.origin, for: .serverURL)
        }
    }

    private func parse(_ url: String?) -> ServerConfig? {
        url.flatMap { try? ServerConfig(parsing: $0) }
    }
}
