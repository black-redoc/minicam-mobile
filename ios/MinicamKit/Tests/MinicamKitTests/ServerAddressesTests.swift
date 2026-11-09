import Foundation
import Testing
@testable import MinicamKit

/// docs/adr/0013: local and remote address of one server, with a switch.
@Suite struct ServerAddressesTests {
    let local = try! ServerConfig(parsing: "http://192.168.1.20:8080")
    let remote = try! ServerConfig(parsing: "https://cam.example.com")

    @Test func serverAddresses_migratesAndKeepsBoth() {
        let store = InMemoryCredentialStore()
        store.set("https://cam.example.com", for: .serverURL)
        let addresses = ServerAddresses(store: store)
        #expect(addresses.remote == remote)
        #expect(addresses.active == remote)
        #expect(addresses.local == nil)

        addresses.add(local)
        #expect(addresses.local == local)
        #expect(addresses.active == remote)

        #expect(addresses.switchTo(.local) == local)
        #expect(addresses.active == local)
        #expect(store.value(for: .serverURL) == "http://192.168.1.20:8080")
        // A fresh instance (next app start) reads the same state.
        #expect(ServerAddresses(store: store).active == local)
        #expect(ServerAddresses(store: store).remote == remote)
    }

    @Test func switchNetwork_keepsSessionAndUsesOtherOrigin() async throws {
        let store = InMemoryCredentialStore()
        let addresses = ServerAddresses(store: store)
        addresses.use(remote)
        store.set("s1", for: .sessionCookie)
        store.set("jwt", for: .accessToken)
        addresses.add(local)

        let target = try #require(addresses.switchTo(.local))
        #expect(addresses.use(target))
        #expect(store.value(for: .sessionCookie) == "s1")
        #expect(store.value(for: .accessToken) == "jwt")

        let transport = FakeTransport([.init(status: 200, body: "[]")])
        _ = try await MinicamAPI(config: target, transport: transport, store: store).cameras()
        let request = try #require(transport.requests.first)
        #expect(request.url?.absoluteString == "http://192.168.1.20:8080/api/me/cameras")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "minicam_session=s1")
        // The Access token only ever goes to the remote address.
        #expect(request.value(forHTTPHeaderField: MinicamAPI.accessTokenHeader) == nil)
        #expect(addresses.switchTo(.remote) == remote)
        #expect(store.value(for: .sessionCookie) == "s1")
    }

    @Test func switchNetwork_toRemoteWithoutTokenNeedsAccess() throws {
        let store = InMemoryCredentialStore()
        let addresses = ServerAddresses(store: store)
        addresses.use(local)
        addresses.add(remote)
        store.set("s1", for: .sessionCookie)

        let target = try #require(addresses.switchTo(.remote))
        #expect(addresses.needsAccessLogin(target))
        #expect(store.value(for: .sessionCookie) == "s1")
        store.set("jwt", for: .accessToken)
        #expect(!addresses.needsAccessLogin(target))
        #expect(!addresses.needsAccessLogin(try #require(addresses.switchTo(.local))))
    }

    @Test func newServer_clearsEverything() throws {
        let store = InMemoryCredentialStore()
        let addresses = ServerAddresses(store: store)
        addresses.use(local)
        addresses.add(remote)
        store.set("s1", for: .sessionCookie)
        store.set("jwt", for: .accessToken)

        #expect(!addresses.use(try ServerConfig(parsing: "https://other.example.com")))
        #expect(store.value(for: .sessionCookie) == nil)
        #expect(store.value(for: .accessToken) == nil)
        #expect(addresses.local == nil)
        #expect(addresses.active?.origin == "https://other.example.com")
    }

    @Test func logout_doesNotWaitForAnUnreachableServer() async throws {
        let store = InMemoryCredentialStore()
        store.set("s1", for: .sessionCookie)
        let transport = SessionCheckingTransport(store: store)
        await MinicamAPI(config: local, transport: transport, store: store).logout()

        #expect(store.value(for: .sessionCookie) == nil)
        let request = try #require(transport.request)
        #expect(request.value(forHTTPHeaderField: "Cookie") == "minicam_session=s1")
        #expect(request.timeoutInterval == MinicamAPI.logoutTimeout)
        // The session was already gone when the request left: nothing waits on the answer.
        #expect(transport.sessionWhenSent == nil)
    }
}

/// Records the logout request and whether the session was still stored when it was sent;
/// then fails like an unreachable host.
private final class SessionCheckingTransport: HTTPTransport, @unchecked Sendable {
    let store: CredentialStore
    private(set) var request: URLRequest?
    private(set) var sessionWhenSent: String?

    init(store: CredentialStore) {
        self.store = store
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        self.request = request
        sessionWhenSent = store.value(for: .sessionCookie)
        throw URLError(.timedOut)
    }
}
