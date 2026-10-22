import Foundation

/// Seam between `MinicamAPI` and the network, so tests can fake minicam.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
    /// Like `send`, but the body goes to a temporary file the caller owns (large files).
    func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse)
}

extension HTTPTransport {
    public func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse) {
        let (data, response) = try await send(request)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try data.write(to: file)
        return (file, response)
    }
}

/// URLSession transport that never follows redirects (a redirect from a WAN
/// host means Cloudflare Access wants a login) and never touches the shared
/// cookie jar: cookies are managed explicitly through `CredentialStore`.
public final class URLSessionTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    public override init() {}

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw MinicamError.invalidResponse }
        return (data, http)
    }

    public func download(_ request: URLRequest) async throws -> (URL, HTTPURLResponse) {
        let (location, response) = try await session.download(for: request)
        guard let http = response as? HTTPURLResponse else {
            try? FileManager.default.removeItem(at: location)
            throw MinicamError.invalidResponse
        }
        return (location, http)
    }

    public func webSocketTask(with request: URLRequest) -> URLSessionWebSocketTask {
        session.webSocketTask(with: request)
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest
    ) async -> URLRequest? {
        nil
    }
}
