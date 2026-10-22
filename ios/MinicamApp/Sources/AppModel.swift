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
    /// The signed-in account; nil until login or the first `GET /api/auth/me`.
    private(set) var user: User?
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
            self.user = try await api.login(email: email, password: password)
            self.phase = .signedIn
            try await self.reloadCameras()
        }
    }

    func reloadCameras() async throws {
        guard let api else { return }
        if user == nil { user = try await api.me() }
        cameras = try await api.cameras()
    }

    func refresh() async {
        await run { try await self.reloadCameras() }
    }

    func logout() async {
        await api?.logout()
        cameras = []
        user = nil
        phase = .signedOut
    }

    /// Central error mapping, shared by every screen.
    func handle(_ error: Error) {
        errorMessage = describe(error)
    }

    /// User-facing message for `error`. A lost session or Access token also signs out here,
    /// so every screen gets the same behavior (spec 001 AC4).
    func describe(_ error: Error) -> String {
        switch error {
        case MinicamError.invalidCredentials:
            return "Invalid email or password"
        case MinicamError.unauthorized:
            signOutLocally()
            return "Your session expired. Sign in again."
        case MinicamError.accessRequired:
            store.set(nil, for: .accessToken)
            signOutLocally()
            return "Sign in with Cloudflare Access again."
        case MinicamError.rateLimited:
            return "Too many attempts. Wait a few minutes."
        case let MinicamError.forbidden(detail), let MinicamError.rejected(detail):
            return detail
        case MinicamError.notFound:
            return "Not found or not allowed."
        case let MinicamError.server(code):
            return "The server failed (\(code))."
        default:
            return "Can't reach the server."
        }
    }

    /// Runs an admin request: returns its result, or nil after reporting the failure
    /// through `onError`.
    func attempt<T>(_ onError: (String) -> Void, _ work: () async throws -> T) async -> T? {
        do {
            return try await work()
        } catch is CancellationError {
            return nil
        } catch {
            onError(describe(error))
            return nil
        }
    }

    private func signOutLocally() {
        user = nil
        phase = .signedOut
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
