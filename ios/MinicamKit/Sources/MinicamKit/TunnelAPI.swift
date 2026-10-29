import Foundation

// minicam's Cloudflare Tunnel endpoints (minicam ADR 0008, docs/adr/0009):
// admins only, LAN only. The device installs cloudflared itself and reports
// progress through `tunnelStatus()`.

public enum TunnelState: String, Decodable, Sendable {
    case none, installing, running, error
}

public struct TunnelStatus: Decodable, Equatable, Sendable {
    public var available: Bool
    public var state: TunnelState
    public var hostname: String?
    public var message: String?

    public init(available: Bool, state: TunnelState, hostname: String? = nil, message: String? = nil) {
        self.available = available
        self.state = state
        self.hostname = hostname
        self.message = message
    }
}

/// What the device needs from Cloudflare; the API token never leaves the app.
public struct TunnelSetup: Encodable, Equatable, Sendable {
    public var tunnelToken: String
    public var hostname: String
    public var teamDomain: String
    public var aud: String

    enum CodingKeys: String, CodingKey {
        case tunnelToken = "tunnel_token", hostname, teamDomain = "team_domain", aud
    }

    public init(tunnelToken: String, hostname: String, teamDomain: String, aud: String) {
        self.tunnelToken = tunnelToken
        self.hostname = hostname
        self.teamDomain = teamDomain
        self.aud = aud
    }
}

extension MinicamAPI {
    /// `notFound` when this installation has no tunnel helper.
    public func tunnelStatus() async throws -> TunnelStatus {
        try await get("/api/system/tunnel")
    }

    public func setUpTunnel(_ setup: TunnelSetup) async throws {
        _ = try await send("POST", "/api/system/tunnel", body: try JSONEncoder().encode(setup))
    }

    public func removeTunnel() async throws {
        _ = try await send("DELETE", "/api/system/tunnel")
    }

    /// Polls until the device reports `running` or `error` (installing takes about a minute).
    public func waitForTunnel(
        interval: Duration = .seconds(2),
        timeout: Duration = .seconds(300),
        sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) async throws -> TunnelStatus {
        var waited = Duration.zero
        while true {
            let status = try await tunnelStatus()
            if status.state == .running || status.state == .error || waited >= timeout {
                return status
            }
            try await sleep(interval)
            waited += interval
        }
    }
}
