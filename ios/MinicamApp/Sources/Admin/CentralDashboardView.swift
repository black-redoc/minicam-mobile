import MinicamKit
import SwiftUI

/// Admin → Connect to dashboard (minicam ADRs 0009/0010, docs/adr/0011): connects the device to
/// minicam-admin with the one-time code shown by "Pair device" there, typed or from its QR link,
/// and shows the remote access the dashboard sets up. minicam exchanges the code for its token
/// itself; the code is only held in this view's state.
struct CentralDashboardView: View {
    @Environment(AppModel.self) private var model
    @State private var status: CentralStatus?
    @State private var url = "https://"
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var success: String?
    @State private var progress: ConnectProgress?
    /// Filled from a QR link: the admin must see the dashboard host and confirm (docs/adr/0011).
    @State private var linkHost: String?

    var body: some View {
        Form {
            Section("Status") {
                if let status {
                    Text(status.connected ? "Connected" : "Not connected").foregroundStyle(Theme.text)
                    if status.connected {
                        if let url = status.url {
                            Text(verbatim: url).font(.caption).foregroundStyle(Theme.muted)
                        }
                        Text(status.lastSyncedAt.map { "Last sync: \($0) (UTC)" } ?? "Waiting for the first sync…")
                            .font(.caption).foregroundStyle(Theme.muted)
                        if status.managedInEnv {
                            Text("Set in the device's .env file: change it there.")
                                .font(.caption).foregroundStyle(Theme.muted)
                        }
                    }
                } else if error == nil {
                    SkeletonRow(index: 0, trailing: false)
                }
            }
            .adminRow()

            if let progress {
                Section("Progress") {
                    ProgressRow(label: "Dashboard", state: progress.dashboard, detail: progress.dashboardText)
                    ProgressRow(label: "Remote access", state: progress.remoteAccess, detail: progress.remoteAccessText)
                }
                .adminRow()
            }

            if status?.managedInEnv != true {
                Section(status?.connected == true ? "Pair again (new token)" : "Connect") {
                    Text("""
                        In the dashboard open Sites, press "Pair device" on this device's site and type the \
                        code it shows (valid once, for 10 minutes), or scan its QR with the phone's camera. \
                        The device fetches its own token and, if the dashboard offers it, its remote access: \
                        nothing secret is copied by hand.
                        """)
                        .font(.caption).foregroundStyle(Theme.muted)
                    if let linkHost {
                        Text("Connect this device to \(linkHost)?")
                            .font(.title3.weight(.semibold)).foregroundStyle(Theme.text)
                        Text("Only continue if you opened this code from your own dashboard: it decides who can watch from outside.")
                            .font(.caption).foregroundStyle(Theme.muted)
                    }
                    LabeledField(label: "Dashboard address") {
                        TextField("https://admin.example.com", text: $url)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    LabeledField(label: "Pairing code") {
                        TextField("ABCD-EFGH", text: $code)
                            .font(.body.monospaced())
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onChange(of: code) { _, value in
                                let formatted = PairingCode.format(value)
                                if formatted != value { code = formatted }
                            }
                    }
                    if status?.connected == true {
                        Button("Disconnect", role: .destructive) { Task { await unpair() } }
                            .disabled(busy)
                    }
                }
                .adminRow()

                Section {
                    SaveButton(label: status?.connected == true ? "Pair again" : "Connect", busy: busy,
                               enabled: !code.trimmingCharacters(in: .whitespaces).isEmpty) {
                        Task { await pair() }
                    }
                }
            }

            if error != nil || success != nil {
                Section { FormMessage(error: error, success: success) }.adminRow()
            }
        }
        .adminForm()
        .navigationTitle("Connect to dashboard")
        .task { await load() }
    }

    private func load() async {
        guard let api = model.api else { return }
        if api.config.isWAN {
            error = "The dashboard can only be connected while you're on your home network."
            return
        }
        let link = model.pendingPairLink.take()
        if let current = await model.attempt({ error = $0 }, { try await api.centralStatus() }) {
            status = current
            if let current = current.url { url = current }
        }
        if let link {
            url = link.url
            code = PairingCode.format(link.code)
            linkHost = link.host
        }
    }

    private func pair() async {
        guard let api = model.api else { return }
        error = nil
        success = nil
        guard let dashboard = CentralURL.normalize(url) else {
            error = "Enter the dashboard's https:// address, e.g. https://admin.example.com"
            return
        }
        guard let pairing = PairingCode.normalize(code) else {
            error = "The code has 8 letters and digits, like ABCD-EFGH."
            return
        }
        busy = true
        defer { busy = false }
        code = ""
        linkHost = nil
        let result = await model.attempt({ error = $0 }, {
            try await api.connectToDashboard(url: dashboard, code: pairing, onProgress: { next in
                await MainActor.run { progress = next }
            })
        })
        guard let result else {
            progress = nil
            return
        }
        if let current = await model.attempt({ _ in }, { try await api.centralStatus() }) { status = current }
        success = "WAN access is now managed in the dashboard."
            + (result.wanURL.map { " From outside your home network, sign in at \($0)" } ?? "")
    }

    private func unpair() async {
        guard let api = model.api else { return }
        error = nil
        success = nil
        busy = true
        defer { busy = false }
        if let current = await model.attempt({ error = $0 }, { try await api.unpairFromCentral() }) {
            status = current
            success = "Disconnected. WAN access is managed on the device again."
        }
    }
}

private struct ProgressRow: View {
    let label: String
    let state: StepState
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(label, systemImage: icon).foregroundStyle(color)
            if let detail { Text(detail).font(.caption).foregroundStyle(Theme.muted) }
        }
    }

    private var icon: String {
        switch state {
        case .waiting: "circle"
        case .working: "hourglass"
        case .done: "checkmark.circle.fill"
        case .skipped: "minus.circle"
        case .failed: "xmark.octagon.fill"
        }
    }

    private var color: Color {
        switch state {
        case .failed: Theme.danger
        case .waiting, .skipped: Theme.muted
        default: Theme.text
        }
    }
}
