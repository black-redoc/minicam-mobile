import AuthenticationServices
import MinicamKit
import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var email = ""
    @State private var password = ""
    @State private var confirm = ""
    @State private var setupCode = ""
    @State private var searching = false

    var body: some View {
        @Bindable var model = model
        let status = model.setupStatus
        let needsCode = status?.needsLEDCode == true

        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                BrandTitle()
                    .padding(.top, 48)

                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(model.creatingAdmin ? "Create admin account" : "Sign in")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.text)
                        Text(model.creatingAdmin
                            ? "No account exists on this device yet. The account you create becomes the administrator."
                            : "Use your minicam account.")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    }

                    field("Server") {
                        TextField("https://cam.example.com", text: $model.serverURLText)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .accessibilityIdentifier("server")
                    }
                    discoveryList

                    if model.needsAccessLogin {
                        Button("Continue with Cloudflare Access") {
                            Task { await accessLogin() }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        Text("This server is outside your network. Cloudflare Access verifies you first.")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    } else if model.creatingAdmin, status?.state == .restartNeeded {
                        Text("""
                            Setup is only open for 30 minutes after the device starts. Unplug the device's \
                            power, plug it back in, wait a minute and try again.
                            """)
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    } else if model.creatingAdmin {
                        setupForm(needsCode: needsCode)
                    } else {
                        signInForm
                    }

                    // Only a device with no account yet can be set up; switching lets the user
                    // sign in instead (or go back to setup) without retyping the address.
                    if status?.setupRequired == true, !model.needsAccessLogin {
                        Button(model.creatingAdmin ? "I already have an account: sign in" : "Set up this device") {
                            model.creatingAdmin.toggle()
                        }
                        .font(.footnote)
                        .foregroundStyle(Theme.accentHover)
                    }

                    if let error = model.errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(Theme.danger)
                    }
                }
                .card()
            }
            .padding(16)
        }
        .background(Theme.bg.ignoresSafeArea())
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        // Ask a LAN server whether it still needs an admin once the address stops changing.
        .task(id: model.serverURLText) {
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled else { return }
            await model.checkSetup()
        }
        .onChange(of: searching) { _, on in
            if on { model.discovery.start() } else { model.discovery.stop() }
        }
        .onDisappear { model.discovery.stop() }
    }

    @ViewBuilder private var signInForm: some View {
        field("Email") {
            TextField("", text: $email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .accessibilityIdentifier("email")
        }
        field("Password") {
            PasswordField(placeholder: "", text: $password, identifier: "password")
        }
        Button {
            Task { await model.login(email: email, password: password) }
        } label: {
            if model.isBusy { ProgressView().tint(Theme.onAccent) } else { Text("Sign in") }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(model.isBusy || email.isEmpty || password.isEmpty)
        .accessibilityIdentifier("signIn")
    }

    @ViewBuilder private func setupForm(needsCode: Bool) -> some View {
        if needsCode {
            Text("To prove you are next to the device, ask it for a code: its green LED blinks 4 digits.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
            Button("Blink a code on the device") {
                Task { await model.requestSetupCode() }
            }
            .buttonStyle(.bordered)
            .disabled(model.isBusy)
            if let message = model.setupCodeMessage {
                Text(message).font(.caption).foregroundStyle(Theme.muted)
            }
            field("Code from the LED") {
                TextField("", text: $setupCode)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.body.monospaced())
                    .onChange(of: setupCode) { _, value in
                        let digits = String(value.filter(\.isNumber).prefix(4))
                        if digits != value { setupCode = digits }
                    }
            }
        }
        field("Email") {
            TextField("", text: $email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
        }
        field("Password (min. 8 characters)") {
            PasswordField(placeholder: "", text: $password, contentType: .newPassword)
        }
        field("Confirm password") {
            PasswordField(placeholder: "", text: $confirm, contentType: .newPassword)
        }
        if !confirm.isEmpty, confirm != password {
            Text("Passwords do not match.").font(.caption).foregroundStyle(Theme.danger)
        }
        Button {
            Task {
                await model.completeSetup(
                    email: email.trimmingCharacters(in: .whitespaces),
                    password: password,
                    setupCode: needsCode ? setupCode : nil
                )
            }
        } label: {
            if model.isBusy { ProgressView().tint(Theme.onAccent) } else { Text("Create account") }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(
            model.isBusy || email.isEmpty || password.count < 8 || password != confirm
                || (needsCode && !SetupCode.isValid(setupCode))
        )
    }

    /// "Find minicam on my network": devices answering `_minicam._tcp` on the Wi-Fi.
    @ViewBuilder private var discoveryList: some View {
        Button(searching ? "Stop searching" : "Find minicam on my network") { searching.toggle() }
            .buttonStyle(.bordered)
        if searching {
            if model.discovery.denied {
                Text("Allow local network access for minicam in Settings → Privacy & Security → Local Network.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            } else if model.discovery.servers.isEmpty {
                Text("Searching… The phone must be on the same Wi-Fi as the device.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
            ForEach(model.discovery.servers) { server in
                Button {
                    model.serverURLText = server.baseURL
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

    private func field(_ label: String, @ViewBuilder input: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(Theme.muted)
            input().minicamInput()
        }
    }

    private func accessLogin() async {
        guard let api = model.applyServerURL() else { return }
        let handoff = AccessHandoff()
        do {
            let callback = try await webAuthenticationSession.authenticate(
                using: handoff.handoffURL(for: api.config),
                callbackURLScheme: AccessHandoff.callbackScheme,
                preferredBrowserSession: .shared
            )
            model.completeAccessLogin(callback: callback, handoff: handoff)
        } catch ASWebAuthenticationSessionError.canceledLogin {
            return
        } catch {
            model.errorMessage = "Cloudflare Access login failed. Try again."
        }
    }
}
