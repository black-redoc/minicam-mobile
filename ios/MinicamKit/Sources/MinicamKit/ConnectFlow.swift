import Foundation

// "Connect to dashboard" (docs/adr/0011): one dashboard code, typed or scanned as a QR, pairs the
// device and brings up its remote access (minicam ADR 0010, minicam-admin ADR 0012).

/// `minicam://pair?url=<dashboard origin>&code=<code>`, the QR on the dashboard's code page.
public struct PairLink: Equatable, Sendable, CustomStringConvertible {
    public let url: String
    public let code: String

    public init(url: String, code: String) {
        self.url = url
        self.code = code
    }

    public var description: String { "PairLink(url: \(url))" }

    /// The dashboard's host, shown for confirmation before anything is sent.
    public var host: String { URLComponents(string: url)?.host ?? url }

    public static let notAPairQR = "That QR is not a minicam dashboard code."

    /// A value read by the in-app scanner (docs/adr/0012): only a pair link is accepted.
    public static func fromScan(_ raw: String?) -> PairLink? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), text.count <= 512,
              let url = URL(string: text)
        else { return nil }
        return parse(url)
    }

    /// The link's dashboard address and code, both validated; nil for anything else.
    public static func parse(_ link: URL) -> PairLink? {
        guard let parts = URLComponents(url: link, resolvingAgainstBaseURL: false),
              parts.scheme == "minicam", parts.host == "pair",
              parts.path.isEmpty, parts.fragment == nil, parts.user == nil,
              let items = parts.queryItems
        else { return nil }
        let names = items.map(\.name)
        guard Set(names) == ["url", "code"], names.count == 2,
              let rawURL = items.first(where: { $0.name == "url" })?.value, rawURL.hasPrefix("https://"),
              let url = CentralURL.normalize(rawURL),
              let rawCode = items.first(where: { $0.name == "code" })?.value,
              let code = PairingCode.normalize(rawCode)
        else { return nil }
        return PairLink(url: url, code: code)
    }
}

/// A link opened while the app could not use it (not signed in as an admin on the LAN). Kept in
/// memory only, for 10 minutes like the code itself, then dropped.
public struct PendingPairLink: Sendable {
    public static let ttl: TimeInterval = 10 * 60
    private var link: PairLink?
    private var storedAt = Date.distantPast

    public init() {}

    public mutating func store(_ value: PairLink, now: Date = Date()) {
        link = value
        storedAt = now
    }

    public func peek(now: Date = Date()) -> PairLink? {
        now.timeIntervalSince(storedAt) < Self.ttl ? link : nil
    }

    /// The link if still fresh; reading it clears it.
    public mutating func take(now: Date = Date()) -> PairLink? {
        defer { link = nil }
        return peek(now: now)
    }
}

/// One progress row of the Connect screen.
public enum StepState: Equatable, Sendable { case waiting, working, done, skipped, failed }

public struct ConnectProgress: Equatable, Sendable {
    public var dashboard: StepState = .waiting
    public var remoteAccess: StepState = .waiting
    public var dashboardText: String?
    public var remoteAccessText: String?
    /// `https://<hostname>` once the tunnel runs.
    public var wanURL: String?

    public init(dashboard: StepState = .waiting, remoteAccess: StepState = .waiting) {
        self.dashboard = dashboard
        self.remoteAccess = remoteAccess
    }

    /// The rows for the pairing answer, before any polling.
    public func afterPairing(_ status: CentralStatus) -> ConnectProgress {
        var next = self
        next.dashboard = .done
        next.dashboardText = status.site.map { "Connected as \($0.name) (\($0.hostname))" } ?? "Connected"
        guard let tunnel = status.tunnel else {
            next.remoteAccess = .skipped
            next.remoteAccessText = "This dashboard does not set up remote access."
            return next
        }
        switch tunnel.state {
        case "installing":
            next.remoteAccess = .working
            next.remoteAccessText = "Setting up https://\(tunnel.hostname ?? "…")… (about a minute)"
                + (tunnel.replaced ? " It replaces the remote access set up from this app." : "")
        case "skipped":
            next.remoteAccess = .skipped
            next.remoteAccessText = tunnel.message
        default:
            next.remoteAccess = .failed
            next.remoteAccessText = tunnel.message ?? "Remote access was not set up."
        }
        return next
    }

    /// The Remote access row from the device's tunnel status while polling.
    public func with(tunnel status: TunnelStatus) -> ConnectProgress {
        var next = self
        switch status.state {
        case .running:
            next.remoteAccess = .done
            next.remoteAccessText = "Ready at https://\(status.hostname ?? "")"
            next.wanURL = status.hostname.map { "https://\($0)" }
        case .error:
            next.remoteAccess = .failed
            next.remoteAccessText = status.message ?? "The tunnel did not start."
        default:
            next.remoteAccess = .working
        }
        return next
    }
}

extension MinicamAPI {
    /// Pairs with the dashboard and, when it handed over remote access, waits for the tunnel.
    /// `onProgress` gets every change; the returned value is the final state.
    public func connectToDashboard(
        url: String,
        code: String,
        onProgress: @Sendable (ConnectProgress) async -> Void = { _ in },
        wait: (@Sendable (MinicamAPI) async throws -> TunnelStatus)? = nil
    ) async throws -> ConnectProgress {
        var progress = ConnectProgress(dashboard: .working)
        await onProgress(progress)
        progress = progress.afterPairing(try await pairWithCentral(url: url, pairingCode: code))
        await onProgress(progress)
        if progress.remoteAccess == .working {
            let status: TunnelStatus
            if let wait { status = try await wait(self) } else { status = try await waitForTunnel() }
            progress = progress.with(tunnel: status)
            await onProgress(progress)
        }
        return progress
    }
}
