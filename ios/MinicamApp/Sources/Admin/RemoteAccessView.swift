import MinicamKit
import SwiftUI

/// Admin → Remote access (docs/adr/0009): sets up the Cloudflare Tunnel, DNS record and
/// Access application, then hands minicam the tunnel. The API token lives only in this
/// view's state: it is never stored, logged or sent to minicam.
struct RemoteAccessView: View {
    @Environment(AppModel.self) private var model
    @State private var status: TunnelStatus?
    @State private var unavailable: String?
    @State private var apiToken = ""
    @State private var accounts: [CloudflareAccount] = []
    @State private var accountID: String?
    @State private var zones: [CloudflareZone] = []
    @State private var zoneID: String?
    @State private var label = "cam"
    @State private var busy = false
    @State private var progress: String?
    @State private var error: String?
    @State private var success: String?

    private var account: CloudflareAccount? { accounts.first { $0.id == accountID } }
    private var zone: CloudflareZone? { zones.first { $0.id == zoneID } }
    private var client: CloudflareClient {
        CloudflareClient(token: apiToken.trimmingCharacters(in: .whitespaces), transport: URLSessionTransport())
    }

    var body: some View {
        Form {
            if let unavailable {
                Section("Remote access") {
                    Text(unavailable).font(.caption).foregroundStyle(Theme.muted)
                }
                .adminRow()
            } else {
                statusSection
                setupSection
            }
        }
        .adminForm()
        .navigationTitle("Remote access")
        .task { await load() }
    }

    @ViewBuilder private var statusSection: some View {
        Section("Status") {
            if let status {
                Text(stateLabel(status.state)).foregroundStyle(Theme.text)
                if let hostname = status.hostname {
                    Text(verbatim: "https://\(hostname)").font(.caption).foregroundStyle(Theme.muted)
                }
                if let message = status.message {
                    Text(message).font(.caption).foregroundStyle(Theme.muted)
                }
                if status.state == .running || status.state == .error {
                    Button("Remove remote access", role: .destructive) { Task { await remove() } }
                        .disabled(busy)
                }
            } else if error == nil {
                SkeletonRow(index: 0, trailing: false)
            }
        }
        .adminRow()
    }

    @ViewBuilder private var setupSection: some View {
        Section("Set up with Cloudflare") {
            Text("""
                You need a domain on Cloudflare. Create an API token at dash.cloudflare.com → My Profile → \
                API Tokens with: Account · Cloudflare Tunnel: Edit, Access: Apps and Policies: Edit, \
                Access: Organizations, Identity Providers, and Groups: Read; Zone · DNS: Edit, Zone: Read. \
                The token is used only for this setup and is never saved.
                """)
                .font(.caption).foregroundStyle(Theme.muted)
            LabeledField(label: "Cloudflare API token") {
                SecureField("Token", text: $apiToken)
                    .font(.body.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Button("Load accounts") { Task { await loadAccounts() } }
                .buttonStyle(.bordered)
                .disabled(apiToken.trimmingCharacters(in: .whitespaces).isEmpty || busy)
            if accounts.count > 1 {
                Picker("Account", selection: $accountID) {
                    ForEach(accounts) { Text($0.name).tag(Optional($0.id)) }
                }
                .onChange(of: accountID) { _, _ in Task { await loadZones() } }
            }
            if !zones.isEmpty {
                if zones.count > 1 {
                    Picker("Domain", selection: $zoneID) {
                        ForEach(zones) { Text($0.name).tag(Optional($0.id)) }
                    }
                }
                LabeledField(label: "Name") {
                    TextField("cam", text: $label)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
                if let zone {
                    Text(verbatim: "Address: https://\(TunnelHostname.make(label: label, zone: zone.name) ?? "…")")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
            }
            if let progress {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(progress).font(.caption).foregroundStyle(Theme.muted)
                }
            }
            FormMessage(error: error, success: success)
        }
        .adminRow()

        if !zones.isEmpty {
            Section {
                SaveButton(label: "Set up remote access", busy: busy, enabled: zone != nil) {
                    Task { await setUp() }
                }
            }
        }
    }

    private func stateLabel(_ state: TunnelState) -> String {
        switch state {
        case .none: "Not set up"
        case .installing: "Installing…"
        case .running: "Running"
        case .error: "Failed"
        }
    }

    private func describe(_ error: Error) -> String {
        switch error {
        case CloudflareError.zeroTrustRequired:
            "This Cloudflare account has no Zero Trust organization. In the Cloudflare dashboard open "
                + "Zero Trust, pick a team name and the free plan, then try again."
        case CloudflareError.api(let message):
            "Cloudflare: \(message)"
        case CloudflareError.invalidResponse:
            "Cloudflare: unexpected response"
        default:
            model.describe(error)
        }
    }

    /// Runs `work` with the view busy; reports failures in the form.
    private func run(_ work: () async throws -> Void) async {
        busy = true
        error = nil
        success = nil
        defer {
            busy = false
            progress = nil
        }
        do {
            try await work()
        } catch {
            self.error = describe(error)
        }
    }

    private func load() async {
        guard let api = model.api else { return }
        if api.config.isWAN {
            unavailable = "Remote access can only be set up while you're connected to your home network."
            return
        }
        do {
            status = try await api.tunnelStatus()
        } catch MinicamError.notFound {
            unavailable = "This minicam installation can't set up remote access by itself. "
                + "Use the Raspberry Pi OS image, or follow the manual steps in its documentation."
        } catch {
            self.error = describe(error)
        }
    }

    private func loadAccounts() async {
        await run {
            accounts = try await client.accounts()
            accountID = accounts.first?.id
            zones = []
            zoneID = nil
            if accounts.isEmpty {
                error = "This token can't see any Cloudflare account."
                return
            }
            zones = try await client.zones(accountID: accounts[0].id)
            zoneID = zones.first?.id
        }
    }

    private func loadZones() async {
        guard let accountID else { return }
        await run {
            zones = try await client.zones(accountID: accountID)
            zoneID = zones.first?.id
        }
    }

    private func setUp() async {
        guard let api = model.api, let account, let zone else { return }
        guard let hostname = TunnelHostname.make(label: label, zone: zone.name) else {
            error = "Use letters, digits and dashes for the name (no dots)."
            return
        }
        guard let email = model.user?.email else {
            error = "Reload the app and try again."
            return
        }
        await run {
            let setup = try await CloudflareSetup(client: client).run(
                account: account, zone: zone, hostname: hostname, adminEmail: email
            ) { step in
                await MainActor.run { progress = Self.progressText(step, hostname: hostname) }
            }
            progress = "Installing the tunnel on the device (about a minute)…"
            try await api.setUpTunnel(setup)
            apiToken = ""
            let result = try await api.waitForTunnel()
            status = result
            switch result.state {
            case .running: success = "Remote access is ready at https://\(hostname)"
            case .error: error = result.message ?? "The device could not start the tunnel."
            default: success = "Still installing on the device. Check back in a minute."
            }
        }
    }

    private func remove() async {
        guard let api = model.api else { return }
        await run {
            try await api.removeTunnel()
            status = try await api.waitForTunnel()
            success = "Remote access removed. The Cloudflare tunnel, DNS record and Access application "
                + "stay in your account."
        }
    }

    private static func progressText(_ step: CloudflareStep, hostname: String) -> String {
        switch step {
        case .zeroTrust: "Checking Zero Trust…"
        case .tunnel: "Creating the tunnel…"
        case .route: "Routing \(hostname) to minicam…"
        case .dns: "Adding the DNS record…"
        case .access: "Protecting it with Cloudflare Access…"
        }
    }
}
