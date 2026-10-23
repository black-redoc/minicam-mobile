import Foundation

public enum StreamStatus: Equatable, Sendable {
    case connecting, connected, disconnected
}

public enum StreamEvent: Sendable {
    case status(StreamStatus)
    /// A session started with this choice; `audioAvailable`: the camera's audio can be turned on.
    case session(StreamChoice, audioAvailable: Bool)
    /// One complete JPEG frame, exactly as sent by minicam (MJPEG).
    case frame(Data)
    /// fMP4 init segment: start a new player (again whenever the camera reconnects).
    case initSegment(mime: String, data: Data)
    /// One fMP4 fragment (`moof` + `mdat`), in order.
    case fragment(Data)
    /// The camera's current detections, to draw over fMP4 video.
    case detections(Detections)
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
///
/// With `preferFMP4` each session uses fMP4 when `canPlay` accepts the camera's codecs (ADR 0006);
/// `audio` asks for the camera's audio too, and quietly goes without it if the server refuses.
public final class StreamClient: Sendable {
    private let api: MinicamAPI
    private let transport: URLSessionTransport
    private let camera: String
    private let policy: ReconnectPolicy
    private let preferFMP4: Bool
    private let audio: Bool
    private let canPlay: @Sendable ([String]) -> Bool

    public init(
        api: MinicamAPI, transport: URLSessionTransport, camera: String,
        policy: ReconnectPolicy = ReconnectPolicy(), preferFMP4: Bool = false, audio: Bool = false,
        canPlay: @escaping @Sendable ([String]) -> Bool = { _ in false }
    ) {
        self.api = api
        self.transport = transport
        self.camera = camera
        self.policy = policy
        self.preferFMP4 = preferFMP4
        self.audio = audio
        self.canPlay = canPlay
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

    private func token() async throws -> StreamToken {
        do {
            return try await api.streamToken(camera: camera, audio: audio)
        } catch MinicamError.forbidden(let detail) {
            // can_listen revoked since the camera list was loaded: carry on without audio.
            guard audio else { throw MinicamError.forbidden(detail) }
            return try await api.streamToken(camera: camera)
        }
    }

    private func runOnce(_ continuation: AsyncThrowingStream<StreamEvent, Error>.Continuation) async throws {
        let token = try await token()
        let choice = chooseStream(token, preferFMP4: preferFMP4, audio: audio, canPlay: canPlay)
        let audioAvailable = chooseStream(token, preferFMP4: preferFMP4, audio: true, canPlay: canPlay).audio
        continuation.yield(.session(choice, audioAvailable: audioAvailable))
        let request = api.streamRequest(camera: camera, token: token.token, format: choice.format, audio: choice.audio)
        let socket = transport.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }

        var announced = false
        var initMime: String?
        try await withTaskCancellationHandler {
            while !Task.isCancelled {
                let message = try await socket.receive()
                let bytes: Data
                switch message {
                case let .data(data):
                    bytes = data
                case let .string(text):
                    switch LiveText.parse(text) {
                    case let .initSegment(mime): initMime = mime
                    case let .detections(detections): continuation.yield(.detections(detections))
                    case nil: break
                    }
                    continue
                @unknown default:
                    continue
                }
                if !announced {
                    continuation.yield(.status(.connected))
                    announced = true
                }
                if choice.format == .mjpeg {
                    continuation.yield(.frame(bytes))
                } else if let mime = initMime {
                    initMime = nil
                    continuation.yield(.initSegment(mime: mime, data: bytes))
                } else {
                    continuation.yield(.fragment(bytes))
                }
            }
        } onCancel: {
            socket.cancel(with: .goingAway, reason: nil)
        }
    }
}
