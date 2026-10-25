import Foundation

/// How long a camera's live view may stream per open (spec 011). Time counts only between
/// `start()` and `stop()`, so pauses (background, reconnects) keep what was already used; once
/// `expired`, the stream stays closed until the camera is opened again with a new budget.
public struct ViewingBudget: Sendable {
    public static let limit: TimeInterval = 30

    private let limit: TimeInterval
    private let clock: @Sendable () -> TimeInterval
    private var used: TimeInterval = 0
    private var runningSince: TimeInterval?

    public init(
        limit: TimeInterval = ViewingBudget.limit,
        clock: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }
    ) {
        self.limit = limit
        self.clock = clock
    }

    /// Seconds left, never below 0.
    public var remaining: TimeInterval {
        max(0, limit - used - (runningSince.map { clock() - $0 } ?? 0))
    }

    public var expired: Bool { remaining == 0 }

    public mutating func start() {
        if runningSince == nil { runningSince = clock() }
    }

    public mutating func stop() {
        if let runningSince { used += clock() - runningSince }
        runningSince = nil
    }
}
