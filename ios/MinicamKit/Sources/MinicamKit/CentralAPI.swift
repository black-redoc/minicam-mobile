import Foundation

// Connection to the central dashboard (minicam ADR 0009, minicam-admin ADR 0011): the admin
// types the dashboard address and the one-time code it shows; minicam exchanges the code for
// its device token itself, so the token never reaches the phone. Admins only, LAN only.

public struct PairedSite: Codable, Equatable, Sendable {
    public let name: String
    public let hostname: String

    public init(name: String, hostname: String) {
        self.name = name
        self.hostname = hostname
    }
}

/// Remote access the dashboard handed over when pairing (minicam ADR 0010): `installing` (poll
/// `tunnelStatus()`), `skipped` (Access is set in the device's .env) or `error`. Nil when the
/// dashboard provisions no remote access.
public struct CentralTunnel: Codable, Equatable, Sendable {
    public var state: String
    public var hostname: String?
    public var message: String?
    /// A tunnel set up from this app was replaced by the dashboard's.
    public var replaced: Bool

    enum CodingKeys: String, CodingKey { case state, hostname, message, replaced }

    public init(state: String, hostname: String? = nil, message: String? = nil, replaced: Bool = false) {
        self.state = state
        self.hostname = hostname
        self.message = message
        self.replaced = replaced
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        state = try c.decode(String.self, forKey: .state)
        hostname = try c.decodeIfPresent(String.self, forKey: .hostname)
        message = try c.decodeIfPresent(String.self, forKey: .message)
        replaced = try c.decodeIfPresent(Bool.self, forKey: .replaced) ?? false
    }
}

public struct CentralStatus: Codable, Equatable, Sendable {
    public let connected: Bool
    public let url: String?
    /// "env": set in the device's .env (read only here); "app": paired from the app.
    public let source: String?
    public let lastSyncedAt: String?
    public let site: PairedSite?
    /// Only in the answer to `pairWithCentral`.
    public let tunnel: CentralTunnel?

    enum CodingKeys: String, CodingKey {
        case connected, url, source, lastSyncedAt = "last_synced_at", site, tunnel
    }

    public init(
        connected: Bool, url: String? = nil, source: String? = nil, lastSyncedAt: String? = nil,
        site: PairedSite? = nil, tunnel: CentralTunnel? = nil
    ) {
        self.connected = connected
        self.url = url
        self.source = source
        self.lastSyncedAt = lastSyncedAt
        self.site = site
        self.tunnel = tunnel
    }

    public var managedInEnv: Bool { source == "env" }
}

public enum PairingCode {
    /// What the dashboard shows (`ABCD-EFGH`), ignoring dashes and spaces; nil otherwise.
    public static func normalize(_ input: String) -> String? {
        let code = input.filter { $0 != "-" && !$0.isWhitespace }.uppercased()
        return code.count == 8 && code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) ? code : nil
    }

    /// What the field shows while typing: up to 8 uppercase letters and digits, with the dash the
    /// dashboard shows inserted once a fifth character is typed (so deleting past it still works).
    public static func format(_ input: String) -> String {
        let code = String(input.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.uppercased().prefix(8))
        return code.count > 4 ? "\(code.prefix(4))-\(code.dropFirst(4))" : code
    }
}

public enum CentralURL {
    /// `https://host` with no path, as minicam requires; nil otherwise.
    public static func normalize(_ input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespaces)
        if text.hasSuffix("/") { text.removeLast() }
        if !text.contains("://") { text = "https://" + text }
        guard let parts = URLComponents(string: text), parts.scheme == "https",
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.path.isEmpty, parts.query == nil, parts.user == nil
        else { return nil }
        return "https://\(host)" + (parts.port.map { ":\($0)" } ?? "")
    }
}

extension MinicamAPI {
    public func centralStatus() async throws -> CentralStatus {
        try await get("/api/system/central")
    }

    /// Pairs (or re-pairs, which rotates the device token) with the dashboard at `url`.
    public func pairWithCentral(url: String, pairingCode: String) async throws -> CentralStatus {
        try await sendJSON("POST", "/api/system/central", body: ["url": url, "pairing_code": pairingCode])
    }

    public func unpairFromCentral() async throws -> CentralStatus {
        let (data, _) = try await send("DELETE", "/api/system/central")
        guard let status = try? JSONDecoder().decode(CentralStatus.self, from: data) else {
            throw MinicamError.invalidResponse
        }
        return status
    }
}
