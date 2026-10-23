import Foundation
import Testing
@testable import MinicamKit

@Suite struct MinicamAPITests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")
    let wan = try! ServerConfig(parsing: "https://cam.example.com")
    let userJSON = #"{"id": 1, "email": "a@example.com", "is_admin": false}"#

    @Test func login_storesSessionCookie() async throws {
        let transport = FakeTransport([.init(
            status: 200, body: userJSON,
            headers: ["Set-Cookie": "minicam_session=jwt123; HttpOnly; Path=/; SameSite=lax"]
        )])
        let store = InMemoryCredentialStore()
        let api = MinicamAPI(config: lan, transport: transport, store: store)

        let user = try await api.login(email: "a@example.com", password: "secret123")

        #expect(user == User(id: 1, email: "a@example.com", isAdmin: false))
        #expect(store.value(for: .sessionCookie) == "jwt123")
        let request = try #require(transport.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.absoluteString == "http://192.168.1.20:8080/api/auth/login")
        let body = try JSONDecoder().decode([String: String].self, from: request.httpBody!)
        #expect(body == ["email": "a@example.com", "password": "secret123"])
    }

    @Test func login_unauthorized_reportsInvalidCredentials() async {
        let transport = FakeTransport([.init(status: 401, body: #"{"detail": "Invalid email or password"}"#)])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        await #expect(throws: MinicamError.invalidCredentials) {
            try await api.login(email: "a@example.com", password: "wrong")
        }
    }

    @Test func requests_carrySessionAndAccessHeaders() async throws {
        let transport = FakeTransport([.init(status: 200, body: "[]")])
        let store = InMemoryCredentialStore()
        store.set("jwt123", for: .sessionCookie)
        store.set("cf-jwt", for: .accessToken)
        let api = MinicamAPI(config: wan, transport: transport, store: store)

        _ = try await api.cameras()

        let request = try #require(transport.requests.first)
        #expect(request.value(forHTTPHeaderField: "Cookie") == "minicam_session=jwt123")
        #expect(request.value(forHTTPHeaderField: "cf-access-token") == "cf-jwt")
    }

    @Test func requests_onLan_neverSendAccessToken() async throws {
        let transport = FakeTransport([.init(status: 200, body: "[]")])
        let store = InMemoryCredentialStore()
        store.set("cf-jwt", for: .accessToken)
        let api = MinicamAPI(config: lan, transport: transport, store: store)

        _ = try await api.cameras()

        #expect(transport.requests.first?.value(forHTTPHeaderField: "cf-access-token") == nil)
    }

    @Test func unauthorized_clearsSession() async {
        let transport = FakeTransport([.init(status: 401, body: #"{"detail": "Not authenticated"}"#)])
        let store = InMemoryCredentialStore()
        store.set("jwt123", for: .sessionCookie)
        let api = MinicamAPI(config: lan, transport: transport, store: store)

        await #expect(throws: MinicamError.unauthorized) { try await api.cameras() }
        #expect(store.value(for: .sessionCookie) == nil)
    }

    @Test func cameras_parsesViewableCameras() async throws {
        let transport = FakeTransport([.init(
            status: 200,
            body: #"[{"name": "entrada", "can_use_ptz": true}, {"name": "patio", "can_use_ptz": false}]"#
        )])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        let cameras = try await api.cameras()

        #expect(cameras == [
            CameraSummary(name: "entrada", canUsePTZ: true),
            CameraSummary(name: "patio", canUsePTZ: false),
        ])
        #expect(transport.requests.first?.url?.path == "/api/me/cameras")
    }

    @Test func ptz_postsAction() async throws {
        let transport = FakeTransport(PTZAction.allCases.map { _ in .init(status: 200, body: "{}") })
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        for action in PTZAction.allCases {
            try await api.ptz(camera: "front door", action: action)
        }

        #expect(transport.requests.map(\.httpMethod) == Array(repeating: "POST", count: 5))
        #expect(transport.requests.map { $0.url!.absoluteString } == [
            "up", "down", "left", "right", "stop",
        ].map { "http://192.168.1.20:8080/api/ptz/front%20door/\($0)" })
    }

    @Test func streamToken_returnsToken() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"token": "st", "expires_in": 30}"#)])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        #expect(try await api.streamToken(camera: "entrada") == StreamToken(token: "st", expiresIn: 30))
        #expect(transport.requests.first?.url?.path == "/api/stream/entrada/token")
    }

    @Test func streamToken_readsMimes() async throws {
        let body = #"{"token": "st", "expires_in": 30, "video_mime": "video/mp4; codecs=\"hvc1.1.6.L90\"", "audio_mime": "video/mp4; codecs=\"hvc1.1.6.L90,mp4a.40.2\""}"#
        let transport = FakeTransport([.init(status: 200, body: body)])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        let token = try await api.streamToken(camera: "front door", audio: true)

        #expect(token.videoMime == #"video/mp4; codecs="hvc1.1.6.L90""#)
        #expect(token.audioMime == #"video/mp4; codecs="hvc1.1.6.L90,mp4a.40.2""#)
        #expect(transport.requests.first?.url?.absoluteString == "http://192.168.1.20:8080/api/stream/front%20door/token?audio=true")
    }

    @Test func cameras_readsCanListen() async throws {
        let transport = FakeTransport([.init(
            status: 200,
            body: #"[{"name": "entrada", "can_use_ptz": false, "can_listen": true}, {"name": "patio", "can_use_ptz": false}]"#
        )])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        // Servers without live audio leave it out: no speaker button.
        #expect(try await api.cameras().map(\.canListen) == [true, false])
    }

    @Test func wanRedirectOrHtml403_meansAccessRequired() async {
        let redirect = FakeTransport([.init(
            status: 302, body: "",
            headers: ["Location": "https://team.cloudflareaccess.com/cdn-cgi/access/login"]
        )])
        await #expect(throws: MinicamError.accessRequired) {
            try await MinicamAPI(config: wan, transport: redirect, store: InMemoryCredentialStore()).cameras()
        }

        let html403 = FakeTransport([.init(status: 403, body: "<html>Forbidden</html>")])
        await #expect(throws: MinicamError.accessRequired) {
            try await MinicamAPI(config: wan, transport: html403, store: InMemoryCredentialStore()).cameras()
        }

        let wanGate = FakeTransport([.init(
            status: 403, body: #"{"detail": "WAN access is not enabled for this account"}"#
        )])
        await #expect(throws: MinicamError.forbidden("WAN access is not enabled for this account")) {
            try await MinicamAPI(config: wan, transport: wanGate, store: InMemoryCredentialStore())
                .streamToken(camera: "entrada")
        }
    }
}
