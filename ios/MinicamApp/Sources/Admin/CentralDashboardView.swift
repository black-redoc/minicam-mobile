import MinicamKit
import SwiftUI

/// Admin → Central dashboard (minicam ADR 0009): connects the device to minicam-admin with the
/// one-time code shown by "Pair device" there. minicam exchanges the code for its token itself;
/// the code is only held in this view's state.
struct CentralDashboardView: View {
    @Environment(AppModel.self) private var model
    @State private var status: CentralStatus?
    @State private var url = "https://"
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var success: String?

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

            if status?.managedInEnv != true {
                Section(status?.connected == true ? "Pair again (new token)" : "Connect") {
                    Text("""
                        In the dashboard open Sites, press "Pair device" on this device's site and type the \
                        code it shows (valid once, for 10 minutes). The device fetches its own token: \
                        nothing secret is copied by hand.
                        """)
                        .font(.caption).foregroundStyle(Theme.muted)
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
        .navigationTitle("Central dashboard")
        .task { await load() }
    }

    private func load() async {
        guard let api = model.api else { return }
        if api.config.isWAN {
            error = "The dashboard can only be connected while you're on your home network."
            return
        }
        if let current = await model.attempt({ error = $0 }, { try await api.centralStatus() }) {
            status = current
            if let current = current.url { url = current }
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
        if let paired = await model.attempt({ error = $0 }, {
            try await api.pairWithCentral(url: dashboard, pairingCode: pairing)
        }) {
            status = paired
            code = ""
            let site = paired.site.map { " as \($0.name) (\($0.hostname))" } ?? ""
            success = "Connected\(site). WAN access is now managed in the dashboard."
        }
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
