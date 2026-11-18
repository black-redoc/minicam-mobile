import MinicamKit
import SwiftUI

/// Pushes Settings from the camera list.
struct SettingsRoute: Hashable {}

/// Settings (docs/adr/0017): the app language for everyone; for admins also the device's minicam
/// version and, when a newer release is out, the button that installs it.
struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section(tr("Language")) {
                Picker(tr("App language"), selection: $model.language) {
                    Text(tr("Phone language")).tag(AppLanguage.system)
                    // Each language's own name, so it can be found in either language.
                    Text(verbatim: "English").tag(AppLanguage.english)
                    Text(verbatim: "Español").tag(AppLanguage.spanish)
                }
            }
            .listRowBackground(Theme.surface)

            if model.user?.isAdmin == true {
                Section(tr("minicam on this device")) { deviceRows }
                    .listRowBackground(Theme.surface)
            }

            Section(tr("App")) {
                Text(tr("minicam for iPhone %s", appVersion))
                    .foregroundStyle(Theme.text)
            }
            .listRowBackground(Theme.surface)
        }
        .adminForm()
        .navigationTitle(tr("Settings"))
        .task { await model.checkForUpdate() }
    }

    @ViewBuilder private var deviceRows: some View {
        if let status = model.systemUpdate {
            HStack {
                Text(tr("Installed: %s", status.current)).foregroundStyle(Theme.text)
                Spacer()
                if status.available, !status.updateAvailable, status.latest != nil {
                    Text(tr("Up to date")).font(.caption).foregroundStyle(Theme.accentHover)
                }
            }
            if !status.available {
                hint(tr("This minicam was not installed from the minicam image, so it can't update itself."))
            } else if status.updateAvailable, let latest = status.latest {
                Text(tr("%s available", latest)).foregroundStyle(Theme.success)
            } else if status.latest == nil {
                hint(tr("Couldn't check GitHub for new releases yet."))
            }
        } else if model.checkingUpdate {
            ProgressView(tr("Checking for updates…"))
        } else {
            hint(tr("This minicam can't be updated from the app. Install the latest image on its SD card."))
        }

        switch model.updateProgress {
        case let .running(target, state, _):
            HStack(spacing: 8) {
                ProgressView()
                Text(progressText(target: target, state: state)).foregroundStyle(Theme.text)
            }
        case let .done(version):
            Text(tr("minicam %s is running.", version)).foregroundStyle(Theme.success)
        case let .failed(message):
            Text(message.map { tr("The update failed: %s", $0) }
                ?? tr("The update did not finish. Check the device and try again."))
                .foregroundStyle(Theme.danger)
        case nil:
            EmptyView()
        }
        if let error = model.updateError {
            Text(error).foregroundStyle(Theme.danger)
        }

        if let status = model.systemUpdate, status.available, model.updateProgress?.isRunning != true {
            if status.updateAvailable, let latest = status.latest {
                hint(tr("The device restarts minicam: live views and recording stop for about a minute."))
                Button(tr("Update to %s", latest)) { model.installUpdate() }
                    .fontWeight(.semibold)
            }
            Button(tr("Check for updates")) { Task { await model.checkForUpdate(refresh: true) } }
                .disabled(model.checkingUpdate)
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(Theme.muted)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    private func progressText(target: String, state: String) -> String {
        switch state {
        case "downloading": tr("Downloading minicam %s…", target)
        case "verifying": tr("Checking the signature…")
        case "installing": tr("Installing minicam %s…", target)
        case "restarting": tr("Restarting minicam…")
        default: tr("Starting the update…")
        }
    }
}
