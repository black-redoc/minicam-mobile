import Foundation

public enum ServerConfigError: Error, Equatable {
    case invalidURL
    case unsupportedScheme
    /// `http://` was used for a host that isn't on the LAN (see docs/SECURITY.md).
    case insecureRemoteHost
}

/// The minicam server the app talks to. Only scheme, host and port are kept:
/// every minicam endpoint lives at the root of the host.
public struct ServerConfig: Equatable, Sendable {
    public let baseURL: URL

    /// `https` hosts go through Cloudflare Tunnel + Access; `http` is LAN only.
    public var isWAN: Bool { baseURL.scheme == "https" }

    public init(parsing input: String) throws {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(), !host.isEmpty
        else { throw ServerConfigError.invalidURL }

        switch scheme {
        case "https":
            break
        case "http":
            guard Self.isLANHost(host) else { throw ServerConfigError.insecureRemoteHost }
        default:
            throw ServerConfigError.unsupportedScheme
        }

        var normalized = URLComponents()
        normalized.scheme = scheme
        normalized.host = host
        normalized.port = components.port
        guard let url = normalized.url else { throw ServerConfigError.invalidURL }
        baseURL = url
    }

    /// Private IPv4 ranges, loopback and mDNS `.local` names.
    public static func isLANHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let octets = host.split(separator: ".", omittingEmptySubsequences: false).compactMap { UInt8($0) }
        guard octets.count == 4, host.split(separator: ".").count == 4 else { return false }
        switch (octets[0], octets[1]) {
        case (10, _), (127, _), (192, 168):
            return true
        case (172, 16...31):
            return true
        default:
            return false
        }
    }

    /// Absolute URL for an API path such as `/api/auth/login`.
    func url(path: String) -> URL {
        URL(string: baseURL.absoluteString + path)!
    }

    /// `ws(s)://host/ws/stream/{camera}?token=…[&format=fmp4[&audio=true]]`, scheme matching the
    /// server. The server only sends audio with fMP4.
    public func streamURL(camera: String, token: String, format: StreamFormat = .mjpeg, audio: Bool = false) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.scheme = isWAN ? "wss" : "ws"
        components.percentEncodedPath = "/ws/stream/" + Self.encodePathSegment(camera)
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        if format == .fmp4 {
            components.queryItems?.append(URLQueryItem(name: "format", value: "fmp4"))
            if audio { components.queryItems?.append(URLQueryItem(name: "audio", value: "true")) }
        }
        return components.url!
    }

    static func encodePathSegment(_ segment: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return segment.addingPercentEncoding(withAllowedCharacters: allowed) ?? segment
    }
}
