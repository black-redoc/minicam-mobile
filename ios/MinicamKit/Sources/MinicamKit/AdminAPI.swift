import Foundation

// Admin-only endpoints (docs/specs/005, ADR 0004). minicam's `require_admin`
// is the authorization check; a non-admin gets `forbidden`.
extension MinicamAPI {
    public func adminCameras() async throws -> [CameraConfig] {
        try await get("/api/cameras")
    }

    /// Adds `camera` when `originalName` is nil, otherwise replaces that camera (rename included).
    @discardableResult
    public func saveCamera(_ camera: CameraConfig, originalName: String?) async throws -> CameraConfig {
        if let originalName {
            return try await sendJSON("PUT", Self.cameraPath(originalName), body: camera)
        }
        return try await sendJSON("POST", "/api/cameras", body: camera)
    }

    public func deleteCamera(name: String) async throws {
        _ = try await send("DELETE", Self.cameraPath(name))
    }

    public func discoverCameras() async throws -> [DiscoveredCamera] {
        try await get("/api/setup/discover")
    }

    public func detectionClasses() async throws -> [String] {
        let response: DetectionClassesResponse = try await get("/api/detection/classes")
        return response.classes
    }

    public func detectionSettings() async throws -> DetectionSettings {
        try await get("/api/detection/settings")
    }

    @discardableResult
    public func saveDetectionSettings(_ settings: DetectionSettings) async throws -> DetectionSettings {
        try await sendJSON("PUT", "/api/detection/settings", body: settings)
    }

    public func modelCatalog() async throws -> ModelCatalog {
        try await get("/api/detection/catalog")
    }

    /// Downloads (if needed), verifies and activates a catalog model on the server.
    public func installCatalogModel(name: String) async throws {
        struct Install: Encodable {
            let name: String
            let activate = true
        }
        let _: ModelPathResponse = try await sendJSON(
            "POST", "/api/detection/catalog/install", body: Install(name: name)
        )
    }

    /// The server downloads the `.onnx` at `url`; returns its path on the server.
    public func fetchModel(url: String) async throws -> String {
        let response: ModelPathResponse = try await sendJSON(
            "POST", "/api/detection/model/fetch", body: ["url": url]
        )
        return response.modelPath
    }

    public func users() async throws -> [User] {
        try await get("/api/auth/users")
    }

    @discardableResult
    public func createUser(email: String, password: String, isAdmin: Bool) async throws -> User {
        try await sendJSON(
            "POST", "/api/auth/users",
            body: CreateUserRequest(email: email, password: password, isAdmin: isAdmin)
        )
    }

    public func deleteUser(id: Int) async throws {
        _ = try await send("DELETE", "/api/auth/users/\(id)")
    }

    public func permissions(userID: Int) async throws -> [CameraPermission] {
        try await get("/api/auth/permissions?user_id=\(userID)")
    }

    public func wanAccess(userID: Int) async throws -> Bool {
        let access: WANAccess = try await get("/api/auth/wan-access?user_id=\(userID)")
        return access.isActive
    }

    /// Every user with their WAN access and permissions, fetched in parallel like the web page.
    public func userAccess() async throws -> [UserAccess] {
        let users = try await users()
        return try await withThrowingTaskGroup(of: (Int, UserAccess).self) { group in
            for (index, user) in users.enumerated() {
                group.addTask {
                    async let permissions = self.permissions(userID: user.id)
                    async let wanAccess = self.wanAccess(userID: user.id)
                    return (index, UserAccess(user: user, wanAccess: try await wanAccess, permissions: try await permissions))
                }
            }
            var result = [UserAccess?](repeating: nil, count: users.count)
            for try await (index, access) in group { result[index] = access }
            return result.compactMap { $0 }
        }
    }

    public func setPermission(_ permission: CameraPermission) async throws {
        let _: CameraPermission = try await sendJSON("POST", "/api/auth/permissions", body: permission)
    }

    public func setWANAccess(userID: Int, active: Bool) async throws {
        let _: WANAccess = try await sendJSON(
            "POST", "/api/auth/wan-access", body: WANAccess(userID: userID, isActive: active)
        )
    }

    public func notificationSettings() async throws -> NotificationSettings {
        try await get("/api/notifications/settings")
    }

    @discardableResult
    public func saveNotificationSettings(_ settings: NotificationSettings) async throws -> NotificationSettings {
        try await sendJSON("PUT", "/api/notifications/settings", body: settings)
    }

    private static func cameraPath(_ name: String) -> String {
        "/api/cameras/\(ServerConfig.encodePathSegment(name))"
    }
}
