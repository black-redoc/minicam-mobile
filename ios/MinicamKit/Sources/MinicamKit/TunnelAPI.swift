import Foundation

// minicam's Cloudflare Tunnel status (minicam ADR 0008). Only the dashboard sets the tunnel
// up (docs/adr/0014); the device installs cloudflared itself and reports progress here.

public enum TunnelState: String, Decodable, Sendable {
    case none, installing, running, error
}

public struct TunnelStatus: Decodable, Equatable, Sendable {
    public var available: Bool
    public var state: TunnelState
    public var hostname: String?
    public var message: String?
    /// "app" (set up by an older app) or "central" (by the dashboard; minicam ADR 0010).
    public var source: String?

    public init(
        available: Bool, state: TunnelState, hostname: String? = nil, message: String? = nil, source: String? = nil
    ) {
        self.available = available
        self.state = state
        self.hostname = hostname
        self.message = message
        self.source = source
    }
}

extension MinicamAPI {
    /// `notFound` when this installation has no tunnel helper.
    public func tunnelStatus() async throws -> TunnelStatus {
        try await get("/api/system/tunnel")
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
