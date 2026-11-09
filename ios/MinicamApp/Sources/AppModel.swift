import Foundation
import MinicamKit
import Observation
import SwiftUI

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
    /// False until the first camera request of this session succeeds (skeleton rows, spec 007).
    private(set) var camerasLoaded = false
    /// The signed-in account; nil until login or the first `GET /api/auth/me`.
    private(set) var user: User?
    private(set) var api: MinicamAPI?
    var serverURLText: String
    var errorMessage: String?
    var isBusy = false

    /// The server's first-run setup status; nil until checked, or for WAN hosts.
    private(set) var setupStatus: SetupStatus?
    /// Login shows "Create admin account" instead of "Sign in" (docs/adr/0008).
    var creatingAdmin = false
    private(set) var setupCodeMessage: String?
    /// A dashboard QR link opened before the app could use it (docs/adr/0011); memory only.
    var pendingPairLink = PendingPairLink()
    /// Open "Connect to dashboard" with the in-app scanner (from Remote access, docs/adr/0012).
    var scanPairQROnOpen = false
    /// The signed-in navigation stack, so a QR link can open "Connect to dashboard".
    var path = NavigationPath()

    /// `_minicam._tcp` browser for "Find minicam on my network".
    let discovery = LanDiscovery()

    let store: CredentialStore = KeychainCredentialStore()
    let transport = URLSessionTransport()

    /// Local and remote address of the server, and which one is active (docs/adr/0013).
    private let addresses: ServerAddresses
    private(set) var activeNetwork: Network?
    private(set) var localAddress: String?
    private(set) var remoteAddress: String?

    init() {
        serverURLText = store.value(for: .serverURL) ?? "https://"
        addresses = ServerAddresses(store: store)
        if let config = addresses.active {
            api = MinicamAPI(config: config, transport: transport, store: store)
            if api?.hasSession == true { phase = .signedIn }
        }
        syncAddresses()
    }

    private func syncAddresses() {
        activeNetwork = addresses.active?.network
        localAddress = addresses.local?.origin
        remoteAddress = addresses.remote?.origin
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
            // One of the saved pair keeps the session; any other address is a new server.
            addresses.use(config)
            if api?.config != config {
                api = MinicamAPI(config: config, transport: transport, store: store)
            }
            syncAddresses()
            return api
        } catch {
            errorMessage = Self.addressError(error)
        }
        return nil
    }

    private static func addressError(_ error: Error) -> String {
        if case ServerConfigError.insecureRemoteHost = error {
            return "Plain http:// is only allowed on your local network. Use https://."
        }
        return "Enter a valid server address, e.g. https://cam.example.com"
    }

    /// Uses the other saved address of the same server (docs/adr/0013). Nothing is sent to the
    /// old one; the session and Access token are kept, so the list reloads without signing in.
    func switchNetwork(to network: Network) async {
        guard let config = addresses.switchTo(network) else { return }
        serverURLText = config.origin
        api = MinicamAPI(config: config, transport: transport, store: store)
        errorMessage = nil
        cameras = []
        camerasLoaded = false
        path = NavigationPath()
        syncAddresses()
        // Remote without an Access token goes back to the login screen's Access button.
        let signedIn = api?.hasSession == true && !addresses.needsAccessLogin(config)
        phase = signedIn ? .signedIn : .signedOut
        if signedIn { await refresh() }
    }

    /// Saves the missing `network` address of this server and switches to it; nil on success.
    func addAddress(_ text: String, network: Network) async -> String? {
        let config: ServerConfig
        do {
            config = try ServerConfig(parsing: text)
        } catch {
            return Self.addressError(error)
        }
        guard config.network == network else {
            return network == .local
                ? "A local address starts with http://, e.g. http://192.168.1.20:8080"
                : "A remote address starts with https://"
        }
        addresses.add(config)
        await switchNetwork(to: network)
        return nil
    }

    func completeAccessLogin(callback: URL, handoff: AccessHandoff) {
        do {
            store.set(try handoff.token(fromCallback: callback), for: .accessToken)
            errorMessage = nil
            // Switched to Remote with a session already: no need to sign in again.
            if api?.hasSession == true {
                phase = .signedIn
                Task { await refresh() }
            }
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

    /// Asks a LAN server whether it still needs its first admin, and switches the login
    /// screen to "Create admin account" if so. WAN hosts are never set up from the app.
    func checkSetup() async {
        guard let config = try? ServerConfig(parsing: serverURLText), !config.isWAN else {
            setupStatus = nil
            creatingAdmin = false
            return
        }
        let probe = MinicamAPI(config: config, transport: transport, store: store)
        let status = try? await probe.setupStatus()
        guard (try? ServerConfig(parsing: serverURLText)) == config else { return }
        setupStatus = status
        creatingAdmin = status?.setupRequired == true
        setupCodeMessage = nil
    }

    /// Has the device's LED blink a new setup code.
    func requestSetupCode() async {
        guard let api = applyServerURL() else { return }
        await run {
            let claim = try await api.requestSetupCode()
            self.setupCodeMessage = "The green LED is blinking a \(claim.digits)-digit code. Count the blinks of "
                + "each digit; a longer pause separates digits, an even longer one starts again. It is valid "
                + "for \(claim.expiresIn / 60) minutes."
        }
    }

    /// Creates the first admin (with the LED code when the device asks for it) and signs in.
    func completeSetup(email: String, password: String, setupCode: String?) async {
        guard let api = applyServerURL() else { return }
        await run {
            self.user = try await api.completeSetup(email: email, password: password, setupCode: setupCode)
            self.phase = .signedIn
            self.setupStatus = nil
            self.creatingAdmin = false
            self.setupCodeMessage = nil
            try await self.reloadCameras()
        }
    }

    /// `minicam://pair?url=…&code=…` from the dashboard's QR (docs/adr/0011). Opens "Connect to
    /// dashboard" with the form filled in; it never sends anything by itself.
    func openPairLink(_ url: URL) {
        guard let link = PairLink.parse(url) else {
            errorMessage = "That link is not a minicam dashboard code."
            return
        }
        pendingPairLink.store(link)
        offerPairLink()
    }

    /// Opens the Connect screen for a pending link once signed in as an admin on the LAN.
    private func offerPairLink() {
        guard pendingPairLink.peek() != nil else { return }
        if phase == .signedIn, api?.config.isWAN == false, user?.isAdmin == true {
            path = NavigationPath([AdminRoute.centralDashboard])
        } else {
            errorMessage = "To use the dashboard code, sign in as an admin while you're on the device's "
                + "home network. The code is kept for 10 minutes."
        }
    }

    func reloadCameras() async throws {
        guard let api else { return }
        if user == nil { user = try await api.me() }
        offerPairLink()
        cameras = try await api.cameras()
        camerasLoaded = true
    }

    func refresh() async {
        await run { try await self.reloadCameras() }
    }

    func logout() async {
        let api = api
        // The screen changes at once; the server is told best-effort (docs/adr/0013).
        cameras = []
        camerasLoaded = false
        user = nil
        path = NavigationPath()
        phase = .signedOut
        await api?.logout()
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
        camerasLoaded = false
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
