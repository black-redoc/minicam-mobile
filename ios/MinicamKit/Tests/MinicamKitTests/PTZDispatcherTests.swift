import Foundation
import Testing
@testable import MinicamKit

/// Answers PTZ requests like URLSession: a cancelled task never reaches the
/// wire. Optionally holds the first request until `release()`.
private final class PTZTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let holdFirst: Bool
    private var statuses: [Int]
    private var sent: [String] = []
    private var gate: CheckedContinuation<Void, Never>?

    init(holdFirst: Bool = false, statuses: [Int] = []) {
        self.holdFirst = holdFirst
        self.statuses = statuses
    }

    var actions: [String] { lock.withLock { sent } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        let action = request.url!.lastPathComponent
        let hold = lock.withLock { holdFirst && sent.isEmpty }
        if hold {
            await withCheckedContinuation { continuation in
                lock.withLock {
                    gate = continuation
                    sent.append(action)
                }
            }
        } else {
            lock.withLock { sent.append(action) }
        }
        let status = lock.withLock { statuses.isEmpty ? 200 : statuses.removeFirst() }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!
        return (Data("{}".utf8), response)
    }

    func release() {
        lock.withLock { gate }?.resume()
    }
}

@MainActor
private func waitUntil(_ condition: () -> Bool) async throws {
    for _ in 0..<500 where !condition() {
        try await Task.sleep(for: .milliseconds(10))
    }
    #expect(condition())
}

@MainActor
@Suite struct PTZDispatcherTests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")

    private func dispatcher(_ transport: PTZTransport, onError: @escaping (Error) -> Void = { _ in }) -> PTZDispatcher {
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())
        return PTZDispatcher(send: { try await api.ptz(camera: "entrada", action: $0) }, onError: onError)
    }

    @Test func tap_sendsDirectionThenStop() async throws {
        let transport = PTZTransport()
        let dispatcher = dispatcher(transport)

        // A quick tap: press and release before the first request is on the wire.
        dispatcher.dispatch(.up)
        dispatcher.dispatch(.stop)

        try await waitUntil { transport.actions.count == 2 }
        #expect(transport.actions == ["up", "stop"])
    }

    @Test func whileARequestIsInFlight_onlyTheLatestActionIsQueued() async throws {
        let transport = PTZTransport(holdFirst: true)
        let dispatcher = dispatcher(transport)

        dispatcher.dispatch(.up)
        try await waitUntil { transport.actions == ["up"] }
        dispatcher.dispatch(.left)
        dispatcher.dispatch(.stop)
        transport.release()

        try await waitUntil { transport.actions.count == 2 }
        try await Task.sleep(for: .milliseconds(100))
        #expect(transport.actions == ["up", "stop"])
    }

    @Test func failure_isReportedAndLaterActionsAreStillSent() async throws {
        let transport = PTZTransport(statuses: [503])
        var errors: [Error] = []
        let dispatcher = dispatcher(transport) { errors.append($0) }

        dispatcher.dispatch(.up)
        try await waitUntil { !errors.isEmpty }
        dispatcher.dispatch(.stop)

        try await waitUntil { transport.actions.count == 2 }
        #expect(transport.actions == ["up", "stop"])
        #expect(errors.map { $0 as? MinicamError } == [.server(503)])
    }
}
