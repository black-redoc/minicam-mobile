import Foundation
import MinicamKit
import Observation

/// App-wide state: which server, whether we have a session, the camera list.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case signedOut
        case signedIn
    }

    private(set) var phase: Phase = .signedOut
    private(set) var cameras: [CameraSummary] = []
    private(set) var api: MinicamAPI?
    var serverURLText: String
    var errorMessage: String?
    var isBusy = false

    let store: CredentialStore = KeychainCredentialStore()
    let transport = URLSessionTransport()

    init() {
        serverURLText = store.value(for: .serverURL) ?? "https://"
        if let saved = store.value(for: .serverURL), let config = try? ServerConfig(parsing: saved) {
            api = MinicamAPI(config: config, transport: transport, store: store)
            if api?.hasSession == true { phase = .signedIn }
        }
    }

    var needsAccessLogin: Bool {
        guard let config = try? ServerConfig(parsing: serverURLText) else { return false }
        return config.isWAN && store.value(for: .accessToken) == nil
    }

    /// Validates the URL and (re)creates the API client for it.
    @discardableResult
    func applyServerURL() -> MinicamAPI? {
        do {
            let config = try ServerConfig(parsing: serverURLText)
            if api?.config != config {
                store.set(nil, for: .accessToken)
                store.clearSession()
                api = MinicamAPI(config: config, transport: transport, store: store)
            }
            store.set(config.baseURL.absoluteString, for: .serverURL)
            return api
        } catch ServerConfigError.insecureRemoteHost {
            errorMessage = "Plain http:// is only allowed on your local network. Use https://."
        } catch {
            errorMessage = "Enter a valid server address, e.g. https://cam.example.com"
        }
        return nil
    }

    func completeAccessLogin(callback: URL, handoff: AccessHandoff) {
        do {
            store.set(try handoff.token(fromCallback: callback), for: .accessToken)
            errorMessage = nil
        } catch {
            errorMessage = "Cloudflare Access login failed. Try again."
        }
    }

    func login(email: String, password: String) async {
        guard let api = applyServerURL() else { return }
        await run {
            _ = try await api.login(email: email, password: password)
            self.phase = .signedIn
            try await self.reloadCameras()
        }
    }

    func reloadCameras() async throws {
        guard let api else { return }
        cameras = try await api.cameras()
    }

    func refresh() async {
        await run { try await self.reloadCameras() }
    }

    func logout() async {
        await api?.logout()
        cameras = []
        phase = .signedOut
    }

    /// Central error mapping, shared by every screen.
    func handle(_ error: Error) {
        switch error {
        case MinicamError.invalidCredentials:
            errorMessage = "Invalid email or password"
        case MinicamError.unauthorized:
            phase = .signedOut
            errorMessage = "Your session expired. Sign in again."
        case MinicamError.accessRequired:
            store.set(nil, for: .accessToken)
            phase = .signedOut
            errorMessage = "Sign in with Cloudflare Access again."
        case MinicamError.rateLimited:
            errorMessage = "Too many attempts. Wait a few minutes."
        case let MinicamError.forbidden(detail):
            errorMessage = detail
        case MinicamError.notFound:
            errorMessage = "Not found or not allowed."
        default:
            errorMessage = "Can't reach the server."
        }
    }

    private func run(_ work: @escaping () async throws -> Void) async {
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await work()
        } catch {
            handle(error)
        }
    }
}
