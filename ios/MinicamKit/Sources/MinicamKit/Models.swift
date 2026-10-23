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
    public let canListen: Bool

    public var id: String { name }

    public init(name: String, canUsePTZ: Bool, canListen: Bool = false) {
        self.name = name
        self.canUsePTZ = canUsePTZ
        self.canListen = canListen
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        canUsePTZ = try container.decode(Bool.self, forKey: .canUsePTZ)
        // Absent on servers older than minicam PR #21, which have no live audio.
        canListen = try container.decodeIfPresent(Bool.self, forKey: .canListen) ?? false
    }

    enum CodingKeys: String, CodingKey {
        case name
        case canUsePTZ = "can_use_ptz"
        case canListen = "can_listen"
    }
}

/// `POST /api/stream/{camera}/token`. The MIME types (with RFC 6381 codecs) are the camera's own
/// stream for `format=fmp4`, `nil` while unknown (ADR 0006); `audioMime` also needs `can_listen`.
public struct StreamToken: Codable, Equatable, Sendable {
    public let token: String
    public let expiresIn: Int
    public let videoMime: String?
    public let audioMime: String?

    public init(token: String, expiresIn: Int, videoMime: String? = nil, audioMime: String? = nil) {
        self.token = token
        self.expiresIn = expiresIn
        self.videoMime = videoMime
        self.audioMime = audioMime
    }

    enum CodingKeys: String, CodingKey {
        case token
        case expiresIn = "expires_in"
        case videoMime = "video_mime"
        case audioMime = "audio_mime"
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
    /// minicam refused the request and said why (its JSON `detail`).
    case rejected(String)
    case server(Int)
    case invalidResponse
}
