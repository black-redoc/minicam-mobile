import Foundation

/// Sends PTZ commands one at a time, in order. An action dispatched while
/// nothing is in flight is sent right away; actions dispatched during a
/// request wait in a one-slot queue where the latest replaces the previous
/// one, so at most one request is on the wire, like desktop `ptz.js`.
///
/// Unlike the desktop, a pending request is never cancelled: URLSession drops
/// a cancelled task before sending it, so a quick tap lost its direction (KI-8).
///
/// A direction is held until the next action: it is sent again every
/// `repeatEvery`, because ONVIF `ContinuousMove` without a timeout stops after
/// the camera's default PTZ timeout. `stop` (or another direction) ends the
/// repeat; a repeat never goes out after the action that replaced it.
@MainActor
public final class PTZDispatcher {
    /// Well under the shortest default PTZ timeouts seen on ONVIF cameras (about 1 s).
    public static let defaultRepeatEvery: Duration = .milliseconds(500)

    private let send: (PTZAction) async throws -> Void
    private let onError: (Error) -> Void
    private let repeatEvery: Duration
    private var sending = false
    private var queued: PTZAction?
    private var held: PTZAction?
    private var repeater: Task<Void, Never>?

    public init(
        send: @escaping (PTZAction) async throws -> Void,
        onError: @escaping (Error) -> Void,
        repeatEvery: Duration = PTZDispatcher.defaultRepeatEvery
    ) {
        self.send = send
        self.onError = onError
        self.repeatEvery = repeatEvery
    }

    public func dispatch(_ action: PTZAction) {
        repeater?.cancel()
        repeater = nil
        held = action == .stop ? nil : action
        if let direction = held {
            repeater = Task { [weak self, repeatEvery] in
                while true {
                    try? await Task.sleep(for: repeatEvery)
                    guard let self, !Task.isCancelled, self.held == direction else { return }
                    self.enqueue(direction)
                }
            }
        }
        enqueue(action)
    }

    private func enqueue(_ action: PTZAction) {
        guard !sending else {
            queued = action
            return
        }
        sending = true
        Task { await drain(action) }
    }

    private func drain(_ first: PTZAction) async {
        var next: PTZAction? = first
        while let action = next {
            do {
                try await send(action)
            } catch {
                onError(error)
            }
            next = queued
            queued = nil
            sending = next != nil
        }
    }
}
