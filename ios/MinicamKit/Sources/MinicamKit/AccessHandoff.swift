import Foundation

public enum AccessHandoffError: Error, Equatable {
    case notACallback
    case stateMismatch
    case missingToken
}

/// Cloudflare Access login through the system browser (docs/adr/0002).
/// One instance per login attempt: it owns the `state` the callback must echo.
public struct AccessHandoff: Sendable {
    public static let callbackScheme = "minicam"
    static let callbackHost = "access-callback"

    public let state: String

    public init(state: String = AccessHandoff.randomState()) {
        self.state = state
    }

    public func handoffURL(for config: ServerConfig) -> URL {
        var components = URLComponents(url: config.baseURL, resolvingAgainstBaseURL: false)!
        components.path = "/api/mobile/access-handoff"
        components.queryItems = [URLQueryItem(name: "state", value: state)]
        return components.url!
    }

    /// Validates `minicam://access-callback?state=…#token=…` and returns the token.
    public func token(fromCallback url: URL) throws -> String {
        guard url.scheme == Self.callbackScheme, url.host == Self.callbackHost,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { throw AccessHandoffError.notACallback }

        let returnedState = components.queryItems?.first { $0.name == "state" }?.value
        guard returnedState == state else { throw AccessHandoffError.stateMismatch }

        var fragment = URLComponents()
        fragment.percentEncodedQuery = components.percentEncodedFragment
        guard let token = fragment.queryItems?.first(where: { $0.name == "token" })?.value,
              !token.isEmpty
        else { throw AccessHandoffError.missingToken }
        return token
    }

    /// 32 random bytes, base64url without padding (43 chars, matches spec 002 AC3).
    public static func randomState() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
