import AuthenticationServices
import MinicamKit
import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        @Bindable var model = model

        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                BrandTitle()
                    .padding(.top, 48)

                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Sign in")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.text)
                        Text("Use your minicam account.")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    }

                    field("Server") {
                        TextField("https://cam.example.com", text: $model.serverURLText)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .accessibilityIdentifier("server")
                    }

                    if model.needsAccessLogin {
                        Button("Continue with Cloudflare Access") {
                            Task { await accessLogin() }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        Text("This server is outside your network. Cloudflare Access verifies you first.")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    } else {
                        field("Email") {
                            TextField("", text: $email)
                                .keyboardType(.emailAddress)
                                .textContentType(.username)
                                .accessibilityIdentifier("email")
                        }
                        field("Password") {
                            SecureField("", text: $password)
                                .textContentType(.password)
                                .accessibilityIdentifier("password")
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
