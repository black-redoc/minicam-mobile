import MinicamKit
import SwiftUI

/// "Local | Remote" (docs/adr/0013): one tap moves between the home-network address and the
/// Cloudflare address of the same server. A side without an address asks for it first.
struct NetworkSwitch: View {
    @Environment(AppModel.self) private var model
    @State private var adding: Network?

    var body: some View {
        HStack(spacing: 0) {
            segment(.local, label: "Local", address: model.localAddress)
            segment(.remote, label: "Remote", address: model.remoteAddress)
        }
        .frame(height: 36)
        .background(Theme.bg)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius))
        .overlay(RoundedRectangle(cornerRadius: Theme.radius).stroke(Theme.border))
        .sheet(item: $adding) { AddAddressSheet(network: $0) }
    }

    private func segment(_ network: Network, label: String, address: String?) -> some View {
        let selected = model.activeNetwork == network
        return Button {
            if address == nil {
                adding = network
            } else {
                Task { await model.switchNetwork(to: network) }
            }
        } label: {
            Text(address == nil ? "+ \(label)" : label)
                .font(.footnote.weight(selected ? .semibold : .medium))
                .foregroundStyle(selected ? Theme.onAccent : address == nil ? Theme.muted : Theme.text)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(selected ? Theme.accent : Theme.bg)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(selected)
        .accessibilityLabel(address == nil ? "Add \(label.lowercased()) address" : label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension Network: Identifiable {
    public var id: String { rawValue }
}

private struct AddAddressSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let network: Network
    @State private var text = ""
    @State private var error: String?
    @State private var searching = false

    private var local: Bool { network == .local }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(local
                    ? "The address of this same minicam on your home Wi-Fi."
                    : "The Cloudflare address of this same minicam, used away from home.")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                TextField(local ? "http://192.168.1.20:8080" : "https://cam.example.com", text: $text)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .minicamInput()
                if local { discovery }
                if let error {
                    Text(error).font(.caption).foregroundStyle(Theme.danger)
                }
                Spacer()
            }
            .padding(16)
            .background(Theme.bg)
            .navigationTitle(local ? "Add local address" : "Add remote address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save and switch") {
                        Task {
                            error = await model.addAddress(text, network: network)
                            if error == nil { dismiss() }
                        }
                    }
                }
            }
            .onAppear { text = local ? "http://" : "https://" }
            .onChange(of: searching) { _, on in on ? model.discovery.start() : model.discovery.stop() }
            .onDisappear { model.discovery.stop() }
        }
    }

    /// "Find minicam on my network", as on the login screen.
    @ViewBuilder private var discovery: some View {
        Button(searching ? "Stop searching" : "Find minicam on my network") { searching.toggle() }
            .buttonStyle(.bordered)
        if searching {
            ForEach(model.discovery.servers) { server in
                Button {
                    text = server.baseURL
                    searching = false
                } label: {
                    // Names come from the network: plain Text only (SECURITY.md).
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: server.name).foregroundStyle(Theme.text)
                        Text(verbatim: server.baseURL).font(.caption).foregroundStyle(Theme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
        }
    }
}
