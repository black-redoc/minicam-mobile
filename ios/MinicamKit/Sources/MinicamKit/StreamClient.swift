import Foundation

public enum StreamStatus: Equatable, Sendable {
    case connecting, connected, disconnected
}

public enum StreamEvent: Sendable {
    case status(StreamStatus)
    /// One complete JPEG frame, exactly as sent by minicam.
    case frame(Data)
}

/// Same recovery behavior as the desktop `stream.js`: wait, then reconnect
/// with a freshly minted stream token.
public struct ReconnectPolicy: Sendable {
    public var delay: Duration

    public init(delay: Duration = .seconds(3)) {
        self.delay = delay
    }
}

/// Live stream of one camera. Iterate `events()` from a task tied to the
/// screen's lifetime; cancelling that task closes the socket.
public final class StreamClient: Sendable {
    private let api: MinicamAPI
    private let transport: URLSessionTransport
    private let camera: String
    private let policy: ReconnectPolicy

    public init(
        api: MinicamAPI, transport: URLSessionTransport, camera: String,
        policy: ReconnectPolicy = ReconnectPolicy()
    ) {
        self.api = api
        self.transport = transport
        self.camera = camera
        self.policy = policy
    }

    public func events() -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                while !Task.isCancelled {
                    continuation.yield(.status(.connecting))
                    do {
                        try await runOnce(continuation)
                    } catch MinicamError.unauthorized {
                        continuation.finish(throwing: MinicamError.unauthorized)
                        return
                    } catch MinicamError.accessRequired {
                        continuation.finish(throwing: MinicamError.accessRequired)
                        return
                    } catch {
                        // Dropped socket, network error, camera restart: retry below.
                    }
                    continuation.yield(.status(.disconnected))
                    try? await Task.sleep(for: policy.delay)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runOnce(_ continuation: AsyncThrowingStream<StreamEvent, Error>.Continuation) async throws {
        let token = try await api.streamToken(camera: camera)
        let socket = transport.webSocketTask(with: api.streamRequest(camera: camera, token: token))
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }

        var announced = false
        try await withTaskCancellationHandler {
            while !Task.isCancelled {
                let message = try await socket.receive()
                guard case let .data(jpeg) = message else { continue }
                if !announced {
                    continuation.yield(.status(.connected))
                    announced = true
                }
                continuation.yield(.frame(jpeg))
            }
        } onCancel: {
            socket.cancel(with: .goingAway, reason: nil)
        }
    }
}
