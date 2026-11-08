import Foundation

// Connection to the central dashboard (minicam ADR 0009, minicam-admin ADR 0011): the admin
// types the dashboard address and the one-time code it shows; minicam exchanges the code for
// its device token itself, so the token never reaches the phone. Admins only, LAN only.

public struct PairedSite: Decodable, Equatable, Sendable {
    public let name: String
    public let hostname: String
}

public struct CentralStatus: Decodable, Equatable, Sendable {
    public let connected: Bool
    public let url: String?
    /// "env": set in the device's .env (read only here); "app": paired from the app.
    public let source: String?
    public let lastSyncedAt: String?
    public let site: PairedSite?

    enum CodingKeys: String, CodingKey {
        case connected, url, source, lastSyncedAt = "last_synced_at", site
    }

    public var managedInEnv: Bool { source == "env" }
}

public enum PairingCode {
    /// What the dashboard shows (`ABCD-EFGH`), ignoring dashes and spaces; nil otherwise.
    public static func normalize(_ input: String) -> String? {
        let code = input.filter { $0 != "-" && !$0.isWhitespace }.uppercased()
        return code.count == 8 && code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) ? code : nil
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
