import Foundation

// Admin API payloads (docs/specs/005, minicam `app/models/config.py` and `app/models/auth.py`).

/// Per-camera notification override: `inherit` follows the global switch.
public enum NotificationMode: String, Codable, CaseIterable, Sendable {
    case inherit, on, off
}

/// `GET /api/cameras` item and the `POST`/`PUT` body. The server always returns
/// `password = ""`; sending it empty on `PUT` keeps the current password.
public struct CameraConfig: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var name: String
    public var host: String
    public var rtspPort: Int
    public var onvifPort: Int
    public var user: String
    public var password: String
    public var channel: Int
    public var detectionClasses: [String]
    public var notifications: NotificationMode

    public var id: String { name }

    public init(
        name: String = "", host: String = "", rtspPort: Int = 554, onvifPort: Int = 80,
        user: String = "admin", password: String = "", channel: Int = 1,
        detectionClasses: [String] = [],
        notifications: NotificationMode = .inherit
    ) {
        self.name = name
        self.host = host
        self.rtspPort = rtspPort
        self.onvifPort = onvifPort
        self.user = user
        self.password = password
        self.channel = channel
        self.detectionClasses = detectionClasses
        self.notifications = notifications
    }

    enum CodingKeys: String, CodingKey {
        case name, host, user, password, channel, notifications
        case rtspPort = "rtsp_port"
        case onvifPort = "onvif_port"
        case detectionClasses = "detection_classes"
    }
}

/// A camera found by `GET /api/setup/discover` (WS-Discovery).
public struct DiscoveredCamera: Codable, Equatable, Hashable, Sendable {
    public let host: String
    public let name: String
    public let hardware: String
    public let location: String

    public init(host: String, name: String, hardware: String = "", location: String = "") {
        self.host = host
        self.name = name
        self.hardware = hardware
        self.location = location
    }
}

struct DetectionClassesResponse: Codable, Sendable {
    let classes: [String]
}

public enum DetectionBackend: String, Codable, CaseIterable, Sendable {
    case none, onnx
}

/// `GET/PUT /api/detection/settings`.
public struct DetectionSettings: Codable, Equatable, Sendable {
    public var backend: DetectionBackend
    public var modelPath: String
    public var confidenceThreshold: Double
    public var inferenceFPS: Double

    public init(
        backend: DetectionBackend = .none, modelPath: String = "",
        confidenceThreshold: Double = 0.5, inferenceFPS: Double = 3
    ) {
        self.backend = backend
        self.modelPath = modelPath
        self.confidenceThreshold = confidenceThreshold
        self.inferenceFPS = inferenceFPS
    }

    enum CodingKeys: String, CodingKey {
        case backend
        case modelPath = "model_path"
        case confidenceThreshold = "confidence_threshold"
        case inferenceFPS = "inference_fps"
    }
}

/// One asset of the minicam-models release, with its install state on the server.
public struct CatalogAsset: Codable, Equatable, Sendable, Identifiable {
    public let name: String
    public let model: String
    public let family: String
    public let precision: String
    public let profile: String
    public let bytes: Int
    public let installed: Bool
    public let active: Bool

    public var id: String { name }
}

public struct ModelCatalog: Codable, Equatable, Sendable {
    public let release: String
    public let assets: [CatalogAsset]
}

struct ModelPathResponse: Codable, Sendable {
    let modelPath: String

    enum CodingKeys: String, CodingKey {
        case modelPath = "model_path"
    }
}

struct CreateUserRequest: Codable, Sendable {
    let email: String
    let password: String
    let isAdmin: Bool

    enum CodingKeys: String, CodingKey {
        case email, password
        case isAdmin = "is_admin"
    }
}

public struct CameraPermission: Codable, Equatable, Sendable {
    public let userID: Int
    public let cameraName: String
    public var canView: Bool
    public var canUsePTZ: Bool
    // Always sent: the server resets a missing can_listen to false (KI-9).
    public var canListen: Bool

    public init(userID: Int, cameraName: String, canView: Bool, canUsePTZ: Bool, canListen: Bool = false) {
        self.userID = userID
        self.cameraName = cameraName
        self.canView = canView
        self.canUsePTZ = canUsePTZ
        self.canListen = canListen
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userID = try container.decode(Int.self, forKey: .userID)
        cameraName = try container.decode(String.self, forKey: .cameraName)
        canView = try container.decode(Bool.self, forKey: .canView)
        canUsePTZ = try container.decode(Bool.self, forKey: .canUsePTZ)
        canListen = try container.decodeIfPresent(Bool.self, forKey: .canListen) ?? false
    }

    enum CodingKeys: String, CodingKey {
        case userID = "user_id"
        case cameraName = "camera_name"
        case canView = "can_view"
        case canUsePTZ = "can_use_ptz"
        case canListen = "can_listen"
    }
}

struct WANAccess: Codable, Sendable {
    let userID: Int
    let isActive: Bool

    enum CodingKeys: String, CodingKey {
        case userID = "user_id"
        case isActive = "is_active"
    }
}

/// Global detection notifications, `GET/PUT /api/notifications/settings`.
public struct NotificationSettings: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var cooldownSeconds: Int

    public init(enabled: Bool = false, cooldownSeconds: Int = 60) {
        self.enabled = enabled
        self.cooldownSeconds = cooldownSeconds
    }

    enum CodingKeys: String, CodingKey {
        case enabled
        case cooldownSeconds = "cooldown_seconds"
    }
}

/// A user as the Admin screen shows it: account, WAN access and camera permissions.
public struct UserAccess: Equatable, Sendable, Identifiable {
    public let user: User
    public var wanAccess: Bool
    public var permissions: [CameraPermission]

    public var id: Int { user.id }

    public init(user: User, wanAccess: Bool, permissions: [CameraPermission]) {
        self.user = user
        self.wanAccess = wanAccess
        self.permissions = permissions
    }
}
