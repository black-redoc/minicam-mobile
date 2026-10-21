import Foundation

public struct User: Codable, Equatable, Sendable {
    public let id: Int
    public let email: String
    public let isAdmin: Bool

    enum CodingKeys: String, CodingKey {
        case id, email
        case isAdmin = "is_admin"
    }
}

/// One entry of `GET /api/me/cameras` (docs/specs/002).
public struct CameraSummary: Codable, Equatable, Hashable, Sendable, Identifiable {
    public let name: String
    public let canUsePTZ: Bool

    public var id: String { name }

    public init(name: String, canUsePTZ: Bool) {
        self.name = name
        self.canUsePTZ = canUsePTZ
    }

    enum CodingKeys: String, CodingKey {
        case name
        case canUsePTZ = "can_use_ptz"
    }
}

struct StreamTokenResponse: Codable, Sendable {
    let token: String
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case token
        case expiresIn = "expires_in"
    }
}

/// Mirrors `_ALLOWED_ACTIONS` in minicam `app/routers/ptz.py`.
public enum PTZAction: String, CaseIterable, Sendable {
    case up, down, left, right, stop
}

public enum MinicamError: Error, Equatable {
    case invalidCredentials
    /// The session is gone (expired, revoked, logged out); it has been cleared.
    case unauthorized
    /// Cloudflare Access redirected or rejected the request: run the Access login again.
    case accessRequired
    case forbidden(String)
    case notFound
    case rateLimited
    case server(Int)
    case invalidResponse
}
