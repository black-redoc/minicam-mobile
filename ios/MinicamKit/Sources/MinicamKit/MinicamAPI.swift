import Foundation

/// REST client for the minicam endpoints the viewer needs
/// (docs/ARCHITECTURE.md "Data flow"). Admin endpoints live in AdminAPI.swift.
public final class MinicamAPI: Sendable {
    static let sessionCookieName = "minicam_session"
    static let accessTokenHeader = "cf-access-token"

    public let config: ServerConfig
    private let transport: HTTPTransport
    private let store: CredentialStore

    public init(config: ServerConfig, transport: HTTPTransport, store: CredentialStore) {
        self.config = config
        self.transport = transport
        self.store = store
    }

    public var hasSession: Bool { store.value(for: .sessionCookie) != nil }

    public func login(email: String, password: String) async throws -> User {
        let body = try JSONEncoder().encode(["email": email, "password": password])
        let (data, response) = try await send("POST", "/api/auth/login", body: body, isLogin: true)
        guard let cookie = Self.sessionCookie(from: response, url: config.url(path: "/")) else {
            throw MinicamError.invalidResponse
        }
        store.set(cookie, for: .sessionCookie)
        return try decode(User.self, data)
    }

    public func logout() async {
        _ = try? await send("POST", "/api/auth/logout")
        store.clearSession()
    }

    public func me() async throws -> User {
        try decode(User.self, try await send("GET", "/api/auth/me").0)
    }

    public func cameras() async throws -> [CameraSummary] {
        try decode([CameraSummary].self, try await send("GET", "/api/me/cameras").0)
    }

    public func streamToken(camera: String) async throws -> String {
        let path = "/api/stream/\(ServerConfig.encodePathSegment(camera))/token"
        return try decode(StreamTokenResponse.self, try await send("POST", path).0).token
    }

    public func ptz(camera: String, action: PTZAction) async throws {
        let path = "/api/ptz/\(ServerConfig.encodePathSegment(camera))/\(action.rawValue)"
        _ = try await send("POST", path)
    }

    /// Request for the stream WebSocket handshake, carrying the same auth headers.
    public func streamRequest(camera: String, token: String) -> URLRequest {
        authorized(URLRequest(url: config.streamURL(camera: camera, token: token)))
    }

    // MARK: - Plumbing

    func get<T: Decodable>(_ path: String) async throws -> T {
        try decode(T.self, try await send("GET", path).0)
    }

    func sendJSON<B: Encodable, T: Decodable>(_ method: String, _ path: String, body: B) async throws -> T {
        try decode(T.self, try await send(method, path, body: try JSONEncoder().encode(body)).0)
    }

    func send(
        _ method: String, _ path: String, body: Data? = nil, isLogin: Bool = false
    ) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: config.url(path: path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await transport.send(authorized(request))
        try check(response, data: data, isLogin: isLogin)
        return (data, response)
    }

    private func authorized(_ request: URLRequest) -> URLRequest {
        var request = request
        if let cookie = store.value(for: .sessionCookie) {
            request.setValue("\(Self.sessionCookieName)=\(cookie)", forHTTPHeaderField: "Cookie")
        }
        if config.isWAN, let token = store.value(for: .accessToken) {
            request.setValue(token, forHTTPHeaderField: Self.accessTokenHeader)
        }
        return request
    }

    private func check(_ response: HTTPURLResponse, data: Data, isLogin: Bool) throws {
        switch response.statusCode {
        case 200..<300:
            return
        case 300..<400:
            // Only Cloudflare Access redirects; minicam's JSON API never does.
            throw MinicamError.accessRequired
        case 401:
            if isLogin { throw MinicamError.invalidCredentials }
            store.clearSession()
            throw MinicamError.unauthorized
        case 403:
            let detail = Self.detail(from: data)
            // Cloudflare's own 403 page is HTML, minicam's is JSON with `detail`.
            if config.isWAN && detail == nil { throw MinicamError.accessRequired }
            throw MinicamError.forbidden(detail ?? "Forbidden")
        case 404:
            throw MinicamError.notFound
        case 429:
            throw MinicamError.rateLimited
        default:
            // minicam's validation errors (400/409/502/503 …) explain themselves.
            if let detail = Self.detail(from: data) { throw MinicamError.rejected(detail) }
            throw MinicamError.server(response.statusCode)
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw MinicamError.invalidResponse
        }
    }

    static func detail(from data: Data) -> String? {
        struct ErrorBody: Decodable { let detail: String }
        return (try? JSONDecoder().decode(ErrorBody.self, from: data))?.detail
    }

    static func sessionCookie(from response: HTTPURLResponse, url: URL) -> String? {
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            if let key = entry.key as? String, let value = entry.value as? String {
                result[key] = value
            }
        }
        return HTTPCookie.cookies(withResponseHeaderFields: headers, for: url)
            .first { $0.name == sessionCookieName }?
            .value
    }
}
