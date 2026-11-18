import Foundation

// Updating the device to a newer minicam release (docs/adr/0017, minicam ADR 0013). Admins only;
// the device downloads, verifies and installs the release itself.

/// `GET /api/system/update`.
public struct SystemUpdate: Codable, Equatable, Sendable {
    /// False on an installation without minicam's update helper (not from the image).
    public var available: Bool
    public var current: String
    public var latest: String?
    public var updateAvailable: Bool
    public var state: String
    /// The release being (or last) installed.
    public var version: String?
    public var message: String?
    public var checkedAt: String?

    public init(
        available: Bool = false,
        current: String = "",
        latest: String? = nil,
        updateAvailable: Bool = false,
        state: String = "idle",
        version: String? = nil,
        message: String? = nil,
        checkedAt: String? = nil
    ) {
        self.available = available
        self.current = current
        self.latest = latest
        self.updateAvailable = updateAvailable
        self.state = state
        self.version = version
        self.message = message
        self.checkedAt = checkedAt
    }

    enum CodingKeys: String, CodingKey {
        case available, current, latest, state, version, message
        case updateAvailable = "update_available"
        case checkedAt = "checked_at"
    }

    public static let busyStates: Set<String> = ["requested", "downloading", "verifying", "installing", "restarting"]

    public var busy: Bool { Self.busyStates.contains(state) }
}

extension MinicamAPI {
    /// The device's version and update; nil from a minicam older than updates (404).
    public func systemUpdate(refresh: Bool = false) async throws -> SystemUpdate? {
        do {
            return try await get("/api/system/update" + (refresh ? "?refresh=true" : ""))
        } catch MinicamError.notFound {
            return nil
        }
    }

    /// Asks the device to install `version`, the release `GET` offered.
    public func startUpdate(version: String) async throws -> SystemUpdate {
        struct Body: Encodable { let version: String }
        return try await sendJSON("POST", "/api/system/update", body: Body(version: version))
    }
}

/// Where an update the admin started from this phone stands.
public enum UpdateProgress: Equatable, Sendable {
    /// Working; `state` is the device's (`downloading` …), or `restarting` while it doesn't answer.
    case running(target: String, state: String, message: String?)
    case done(version: String)
    case failed(message: String?)

    public var isRunning: Bool {
        if case .running = self { return true }
        return false
    }
}

/// Follows an update by polling: minicam stops answering while it restarts, and the update is
/// over once it answers with the new version, or reports an error.
public struct UpdateTracker: Sendable {
    public static let pollInterval: Duration = .seconds(3)
    /// Download, unpack and a 3-minute health check on a Pi, with room to spare.
    public static let timeout: TimeInterval = 20 * 60
    /// The helper may not have picked the request up yet right after it was sent.
    public static let grace: TimeInterval = 15

    public let target: String
    public let startedAt: Date

    public init(target: String, startedAt: Date) {
        self.target = target
        self.startedAt = startedAt
    }

    /// The progress after a successful poll.
    public func progress(after status: SystemUpdate, at now: Date) -> UpdateProgress {
        if status.current == target { return .done(version: target) }
        if status.state == "error" { return .failed(message: status.message) }
        // "done" for another version, or idle again: the helper restored the old release.
        if !status.busy, now.timeIntervalSince(startedAt) > Self.grace { return .failed(message: status.message) }
        return timedOut(at: now) ?? .running(target: target, state: status.state, message: status.message)
    }

    /// The progress after a failed poll: expected while minicam restarts.
    public func progressWhenUnreachable(at now: Date) -> UpdateProgress {
        timedOut(at: now) ?? .running(target: target, state: "restarting", message: nil)
    }

    private func timedOut(at now: Date) -> UpdateProgress? {
        now.timeIntervalSince(startedAt) > Self.timeout ? .failed(message: nil) : nil
    }
}
