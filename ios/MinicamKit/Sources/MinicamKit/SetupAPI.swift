import Foundation

// First-admin setup from the app (docs/adr/0008, minicam ADR 0006): the login screen
// asks the server whether it still needs an admin and, on the Raspberry Pi OS image,
// has its LED blink a code the user types in.

public enum DeviceSetupState: Equatable, Sendable {
    /// An admin exists: sign in.
    case configured
    /// No admin yet and setup is open: create one.
    case ready
    /// No admin yet but the 30-minute window is over: power-cycle the device.
    case restartNeeded
}

public struct SetupStatus: Decodable, Equatable, Sendable {
    public var setupRequired: Bool
    /// "none": any LAN client may create the admin; "led": it needs the LED code.
    public var claim: String
    public var windowOpen: Bool
    public var windowSecondsLeft: Int

    enum CodingKeys: String, CodingKey {
        case setupRequired = "setup_required", claim, windowOpen = "window_open"
        case windowSecondsLeft = "window_seconds_left"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        setupRequired = try container.decode(Bool.self, forKey: .setupRequired)
        // Older servers only send setup_required.
        claim = try container.decodeIfPresent(String.self, forKey: .claim) ?? "none"
        windowOpen = try container.decodeIfPresent(Bool.self, forKey: .windowOpen) ?? false
        windowSecondsLeft = try container.decodeIfPresent(Int.self, forKey: .windowSecondsLeft) ?? 0
    }

    public var needsLEDCode: Bool { claim == "led" }

    public var state: DeviceSetupState {
        if !setupRequired { return .configured }
        return claim == "none" || windowOpen ? .ready : .restartNeeded
    }
}

public struct SetupClaim: Decodable, Equatable, Sendable {
    public let digits: Int
    public let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case digits, expiresIn = "expires_in"
    }
}

public enum SetupCode {
    /// The LED blinks 4 digits, each 1-9 (a digit is a number of blinks, so never 0).
    public static func isValid(_ code: String) -> Bool {
        code.count == 4 && code.allSatisfy { ("1"..."9").contains($0) }
    }
}

public enum DiscoveredServers {
    public static let serviceType = "_minicam._tcp"

    /// Base URL for a resolved service, or nil unless it is a LAN address: the same rule as
    /// a typed URL, so discovery can't point the app at a public host.
    public static func baseURL(host: String, port: Int) -> String? {
        guard (1...65535).contains(port),
              let config = try? ServerConfig(parsing: "http://\(host):\(port)"),
              !config.isWAN
        else { return nil }
        return config.baseURL.absoluteString
    }
}

extension MinicamAPI {
    /// Public: works before any account exists.
    public func setupStatus() async throws -> SetupStatus {
        try await get("/api/auth/setup")
    }

    /// Has the device's LED blink a new code (LAN, LED claim, setup window).
    public func requestSetupCode() async throws -> SetupClaim {
        let (data, _) = try await send("POST", "/api/auth/setup/claim")
        guard let claim = try? JSONDecoder().decode(SetupClaim.self, from: data) else {
            throw MinicamError.invalidResponse
        }
        return claim
    }
}
