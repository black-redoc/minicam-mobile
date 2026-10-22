import Foundation

/// Sends PTZ commands one at a time, in order. An action dispatched while
/// nothing is in flight is sent right away; actions dispatched during a
/// request wait in a one-slot queue where the latest replaces the previous
/// one, so at most one request is on the wire, like desktop `ptz.js`.
///
/// Unlike the desktop, a pending request is never cancelled: URLSession drops
/// a cancelled task before sending it, so a quick tap lost its direction (KI-8).
@MainActor
public final class PTZDispatcher {
    private let send: (PTZAction) async throws -> Void
    private let onError: (Error) -> Void
    private var sending = false
    private var queued: PTZAction?

    public init(send: @escaping (PTZAction) async throws -> Void, onError: @escaping (Error) -> Void) {
        self.send = send
        self.onError = onError
    }

    public func dispatch(_ action: PTZAction) {
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
