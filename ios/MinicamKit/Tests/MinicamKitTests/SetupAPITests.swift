import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/013: LAN discovery and first setup.
@Suite struct SetupAPITests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")

    func status(_ json: String) throws -> SetupStatus {
        try JSONDecoder().decode(SetupStatus.self, from: Data(json.utf8))
    }

    func target(_ request: URLRequest) -> String {
        "\(request.httpMethod ?? "") \(request.url!.path(percentEncoded: true))"
    }

    @Test func discoveredServer_buildsLanBaseUrl() {
        #expect(DiscoveredServers.baseURL(host: "192.168.1.37", port: 8080) == "http://192.168.1.37:8080")
        #expect(DiscoveredServers.baseURL(host: "minicam-sebas.local", port: 8080) == "http://minicam-sebas.local:8080")
        #expect(DiscoveredServers.baseURL(host: "8.8.8.8", port: 8080) == nil)
        #expect(DiscoveredServers.baseURL(host: "example.com", port: 8080) == nil)
        #expect(DiscoveredServers.baseURL(host: "192.168.1.37", port: 0) == nil)
    }

    @Test func setupStatus_mapsToDeviceState() async throws {
        let transport = FakeTransport([.init(
            status: 200,
            body: #"{"setup_required": true, "claim": "led", "window_open": true, "window_seconds_left": 1200}"#
        )])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        let current = try await api.setupStatus()

        #expect(current.state == .ready)
        #expect(current.needsLEDCode)
        #expect(current.windowSecondsLeft == 1200)
        #expect(target(transport.requests[0]) == "GET /api/auth/setup")
        #expect(try status(#"{"setup_required": true, "claim": "led", "window_open": false}"#).state == .restartNeeded)
        #expect(try status(#"{"setup_required": false, "claim": "led"}"#).state == .configured)
        #expect(try status(#"{"setup_required": true}"#).state == .ready)
        #expect(try !status(#"{"setup_required": true}"#).needsLEDCode)
    }

    @Test func setupCode_validates() {
        #expect(SetupCode.isValid("2468"))
        for bad in ["", "246", "24680", "2408", "24a8", " 246"] {
            #expect(!SetupCode.isValid(bad))
        }
    }

    @Test func setupClaim_andCompleteSetup() async throws {
        let store = InMemoryCredentialStore()
        let transport = FakeTransport([
            .init(status: 200, body: #"{"digits": 4, "expires_in": 300}"#),
            .init(
                status: 201, body: #"{"id": 1, "email": "admin@example.com", "is_admin": true}"#,
                headers: ["Set-Cookie": "minicam_session=abc; Path=/; HttpOnly"]
            ),
        ])
        let api = MinicamAPI(config: lan, transport: transport, store: store)

        #expect(try await api.requestSetupCode() == SetupClaim(digits: 4, expiresIn: 300))
        let user = try await api.completeSetup(email: "admin@example.com", password: "correct horse", setupCode: "2468")

        #expect(user == User(id: 1, email: "admin@example.com", isAdmin: true))
        #expect(store.value(for: .sessionCookie) == "abc")
        #expect(target(transport.requests[0]) == "POST /api/auth/setup/claim")
        let body = try JSONSerialization.jsonObject(with: transport.requests[1].httpBody!) as! NSDictionary
        #expect(body == ["email": "admin@example.com", "password": "correct horse", "setup_code": "2468"])
    }

    @Test func setupErrors_mapToMessages() async throws {
        let store = InMemoryCredentialStore()
        let transport = FakeTransport([
            .init(status: 403, body: #"{"detail": "Setup is closed. Restart the device to set it up."}"#),
            .init(status: 403, body: #"{"detail": "Wrong code. Ask the device for a new one."}"#),
            .init(status: 429, body: #"{"detail": "Wait a few seconds before asking for a new code."}"#),
            .init(status: 409, body: #"{"detail": "Setup already completed"}"#),
        ])
        let api = MinicamAPI(config: lan, transport: transport, store: store)

        await #expect(throws: MinicamError.forbidden("Setup is closed. Restart the device to set it up.")) {
            try await api.requestSetupCode()
        }
        await #expect(throws: MinicamError.forbidden("Wrong code. Ask the device for a new one.")) {
            try await api.completeSetup(email: "a@b.c", password: "password1", setupCode: "1111")
        }
        await #expect(throws: MinicamError.rateLimited) { try await api.requestSetupCode() }
        await #expect(throws: MinicamError.rejected("Setup already completed")) {
            try await api.completeSetup(email: "a@b.c", password: "password1", setupCode: "1111")
        }
        #expect(store.value(for: .sessionCookie) == nil)
    }
}
