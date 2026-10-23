import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/005: admin endpoints.
@Suite struct AdminAPITests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")
    let cameraJSON = """
        {"name": "front door", "host": "192.168.1.100", "rtsp_port": 554, "onvif_port": 8000,
         "user": "admin", "password": "", "channel": 1, "stream": "sub",
         "detection_classes": ["person"], "notifications": "on"}
        """

    func api(_ transport: HTTPTransport) -> MinicamAPI {
        MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())
    }

    func json(_ request: URLRequest?) throws -> NSDictionary {
        let body = try #require(request?.httpBody)
        return try #require(try JSONSerialization.jsonObject(with: body) as? NSDictionary)
    }

    func json(_ text: String) -> NSDictionary {
        try! JSONSerialization.jsonObject(with: Data(text.utf8)) as! NSDictionary
    }

    func target(_ request: URLRequest?) -> String {
        guard let url = request?.url else { return "" }
        let query = url.query.map { "?\($0)" } ?? ""
        return "\(request?.httpMethod ?? "") \(url.path(percentEncoded: true))\(query)"
    }

    @Test func me_parsesAdminFlag() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"id": 7, "email": "root@example.com", "is_admin": true}"#)])

        #expect(try await api(transport).me() == User(id: 7, email: "root@example.com", isAdmin: true))
        #expect(target(transport.requests.first) == "GET /api/auth/me")
    }

    @Test func adminCameras_parsesConfigs() async throws {
        let transport = FakeTransport([.init(status: 200, body: "[\(cameraJSON)]")])

        let cameras = try await api(transport).adminCameras()

        #expect(cameras == [CameraConfig(
            name: "front door", host: "192.168.1.100", onvifPort: 8000, stream: .sub,
            detectionClasses: ["person"], notifications: .on
        )])
        #expect(target(transport.requests.first) == "GET /api/cameras")
    }

    @Test func saveCamera_postsNewAndPutsExisting() async throws {
        let transport = FakeTransport([.init(status: 201, body: cameraJSON), .init(status: 200, body: cameraJSON)])
        let api = api(transport)
        let camera = CameraConfig(name: "patio", host: "192.168.1.101", password: "secret")

        try await api.saveCamera(camera, originalName: nil)
        var edited = camera
        edited.password = ""
        try await api.saveCamera(edited, originalName: "front door")

        #expect(target(transport.requests[0]) == "POST /api/cameras")
        #expect(try json(transport.requests[0]) == json("""
            {"name": "patio", "host": "192.168.1.101", "rtsp_port": 554, "onvif_port": 80,
             "user": "admin", "password": "secret", "channel": 1, "stream": "main",
             "detection_classes": [], "notifications": "inherit"}
            """))
        #expect(target(transport.requests[1]) == "PUT /api/cameras/front%20door")
        #expect(try json(transport.requests[1])["password"] as? String == "")
    }

    @Test func deleteCamera_sendsDelete() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"status": "ok"}"#)])

        try await api(transport).deleteCamera(name: "front door")

        #expect(target(transport.requests.first) == "DELETE /api/cameras/front%20door")
    }

    @Test func discover_parsesCameras() async throws {
        let transport = FakeTransport([.init(
            status: 200, body: #"[{"host": "192.168.1.105", "name": "IPCamera", "hardware": "IPC", "location": "x"}]"#
        )])

        #expect(try await api(transport).discoverCameras() == [
            DiscoveredCamera(host: "192.168.1.105", name: "IPCamera", hardware: "IPC", location: "x"),
        ])
        #expect(target(transport.requests.first) == "GET /api/setup/discover")
    }

    @Test func detectionClasses_parsesClasses() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"classes": ["person", "car"]}"#)])

        #expect(try await api(transport).detectionClasses() == ["person", "car"])
        #expect(target(transport.requests.first) == "GET /api/detection/classes")
    }

    @Test func catalog_parsesAssets() async throws {
        let transport = FakeTransport([.init(status: 200, body: """
            {"catalog_url": "https://example.com/manifest.json", "release": "v0.1.0", "assets": [
             {"name": "yolo11n-int8.onnx", "model": "yolo11n", "family": "YOLO11", "precision": "int8",
              "profile": "Raspberry Pi 5 (CPU)", "bytes": 4590317, "sha256": "1ca2", "url": "https://x",
              "installed": true, "active": true}]}
            """)])

        let catalog = try await api(transport).modelCatalog()

        #expect(catalog.release == "v0.1.0")
        #expect(catalog.assets == [CatalogAsset(
            name: "yolo11n-int8.onnx", model: "yolo11n", family: "YOLO11", precision: "int8",
            profile: "Raspberry Pi 5 (CPU)", bytes: 4590317, installed: true, active: true
        )])
    }

    @Test func installCatalogModel_postsName() async throws {
        let transport = FakeTransport([.init(
            status: 200, body: #"{"model_path": "/m/yolo11n-int8.onnx", "sha256": "1ca2", "active": true}"#
        )])

        try await api(transport).installCatalogModel(name: "yolo11n-int8.onnx")

        #expect(target(transport.requests.first) == "POST /api/detection/catalog/install")
        #expect(try json(transport.requests.first) == json(#"{"name": "yolo11n-int8.onnx", "activate": true}"#))
    }

    @Test func detectionSettings_roundTrip() async throws {
        let body = #"{"backend": "onnx", "model_path": "/m/a.onnx", "confidence_threshold": 0.4, "inference_fps": 2.5}"#
        let transport = FakeTransport([.init(status: 200, body: body), .init(status: 200, body: body)])
        let api = api(transport)

        let settings = try await api.detectionSettings()
        try await api.saveDetectionSettings(settings)

        #expect(settings == DetectionSettings(backend: .onnx, modelPath: "/m/a.onnx", confidenceThreshold: 0.4, inferenceFPS: 2.5))
        #expect(target(transport.requests[0]) == "GET /api/detection/settings")
        #expect(target(transport.requests[1]) == "PUT /api/detection/settings")
        #expect(try json(transport.requests[1]) == json(body))
    }

    @Test func fetchModel_returnsModelPath() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"model_path": "/m/b.onnx"}"#)])

        #expect(try await api(transport).fetchModel(url: "https://example.com/b.onnx") == "/m/b.onnx")
        #expect(target(transport.requests.first) == "POST /api/detection/model/fetch")
        #expect(try json(transport.requests.first) == json(#"{"url": "https://example.com/b.onnx"}"#))
    }

    @Test func users_listAndCreate() async throws {
        let transport = FakeTransport([
            .init(status: 200, body: #"[{"id": 1, "email": "a@example.com", "is_admin": true}]"#),
            .init(status: 201, body: #"{"id": 2, "email": "b@example.com", "is_admin": false}"#),
        ])
        let api = api(transport)

        #expect(try await api.users() == [User(id: 1, email: "a@example.com", isAdmin: true)])
        #expect(try await api.createUser(email: "b@example.com", password: "password1", isAdmin: false)
            == User(id: 2, email: "b@example.com", isAdmin: false))

        #expect(target(transport.requests[0]) == "GET /api/auth/users")
        #expect(target(transport.requests[1]) == "POST /api/auth/users")
        #expect(try json(transport.requests[1]) == json(#"{"email": "b@example.com", "password": "password1", "is_admin": false}"#))
    }

    @Test func deleteUser_sendsDelete() async throws {
        let transport = FakeTransport([.init(status: 204, body: "")])

        try await api(transport).deleteUser(id: 2)

        #expect(target(transport.requests.first) == "DELETE /api/auth/users/2")
    }

    @Test func userAccess_parsesPermissionsAndWanAccess() async throws {
        // Parallel requests: answer by path, not by order.
        let transport = RoutingTransport([
            "/api/auth/users": #"[{"id": 2, "email": "b@example.com", "is_admin": false}]"#,
            "/api/auth/permissions": #"[{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false}]"#,
            "/api/auth/wan-access": #"{"user_id": 2, "is_active": true}"#,
        ])

        let access = try await api(transport).userAccess()

        #expect(access == [UserAccess(
            user: User(id: 2, email: "b@example.com", isAdmin: false), wanAccess: true,
            permissions: [CameraPermission(userID: 2, cameraName: "patio", canView: true, canUsePTZ: false)]
        )])
        #expect(Set(transport.targets) == [
            "/api/auth/users", "/api/auth/permissions?user_id=2", "/api/auth/wan-access?user_id=2",
        ])
    }

    @Test func setPermission_postsBody() async throws {
        let transport = FakeTransport([.init(
            status: 200, body: #"{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": true}"#
        )])

        try await api(transport).setPermission(CameraPermission(userID: 2, cameraName: "patio", canView: true, canUsePTZ: true))

        #expect(target(transport.requests.first) == "POST /api/auth/permissions")
        #expect(try json(transport.requests.first)
            == json(#"{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": true, "can_listen": false}"#))
    }

    /// KI-9: a missing can_listen is reset to false by the server, so it is always sent.
    @Test func setPermission_sendsCanListen() async throws {
        let transport = FakeTransport([.init(
            status: 200, body: #"{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false, "can_listen": true}"#
        )])

        try await api(transport).setPermission(
            CameraPermission(userID: 2, cameraName: "patio", canView: true, canUsePTZ: false, canListen: true)
        )

        #expect(try json(transport.requests.first)
            == json(#"{"user_id": 2, "camera_name": "patio", "can_view": true, "can_use_ptz": false, "can_listen": true}"#))
    }

    @Test func setWanAccess_postsBody() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"user_id": 2, "is_active": false}"#)])

        try await api(transport).setWANAccess(userID: 2, active: false)

        #expect(target(transport.requests.first) == "POST /api/auth/wan-access")
        #expect(try json(transport.requests.first) == json(#"{"user_id": 2, "is_active": false}"#))
    }

    @Test func notificationSettings_roundTrip() async throws {
        let transport = FakeTransport([
            .init(status: 200, body: #"{"enabled": true, "cooldown_seconds": 120}"#),
            .init(status: 200, body: #"{"enabled": false, "cooldown_seconds": 30}"#),
        ])
        let api = api(transport)

        #expect(try await api.notificationSettings() == NotificationSettings(enabled: true, cooldownSeconds: 120))
        #expect(try await api.saveNotificationSettings(NotificationSettings(enabled: false, cooldownSeconds: 30))
            == NotificationSettings(enabled: false, cooldownSeconds: 30))

        #expect(target(transport.requests[0]) == "GET /api/notifications/settings")
        #expect(target(transport.requests[1]) == "PUT /api/notifications/settings")
        #expect(try json(transport.requests[1]) == json(#"{"enabled": false, "cooldown_seconds": 30}"#))
    }

    @Test func errorDetail_isReported() async {
        let transport = FakeTransport([
            .init(status: 409, body: #"{"detail": "Email already registered"}"#),
            .init(status: 422, body: #"{"detail": [{"loc": ["body", "rtsp_port"], "msg": "too big"}]}"#),
        ])
        let api = api(transport)

        await #expect(throws: MinicamError.rejected("Email already registered")) {
            try await api.createUser(email: "a@example.com", password: "password1", isAdmin: false)
        }
        // FastAPI's validation `detail` is a list, not a message.
        await #expect(throws: MinicamError.server(422)) { try await api.adminCameras() }
    }
}

/// Answers each request with the body registered for its path, in any order.
final class RoutingTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let bodies: [String: String]
    private(set) var targets: [String] = []

    init(_ bodies: [String: String]) {
        self.bodies = bodies
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = request.url!
        lock.withLock { targets.append(url.path + (url.query.map { "?\($0)" } ?? "")) }
        let body = bodies[url.path]
        let response = HTTPURLResponse(
            url: url, statusCode: body == nil ? 404 : 200, httpVersion: "HTTP/1.1", headerFields: [:]
        )!
        return (Data((body ?? "").utf8), response)
    }
}
