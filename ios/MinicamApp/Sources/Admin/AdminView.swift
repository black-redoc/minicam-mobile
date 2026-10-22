import MinicamKit
import SwiftUI

/// The desktop "Admin" page (minicam `admin.html`): users, permissions, WAN access, notifications.
struct AdminView: View {
    @Environment(AppModel.self) private var model
    @State private var cameras: [String] = []
    @State private var users: [UserAccess]?
    @State private var usersError: String?
    @State private var pendingDelete: UserAccess?

    @State private var email = ""
    // Plain @State: the new user's password is never persisted (SECURITY.md).
    @State private var password = ""
    @State private var isAdmin = false
    @State private var creating = false
    @State private var createError: String?

    @State private var notifications: NotificationSettings?
    @State private var cooldown = ""
    @State private var savingNotifications = false
    @State private var notificationsError: String?
    @State private var notificationsSaved = false

    @State private var recordings: RecordingSettings?
    @State private var savingRecordings = false
    @State private var recordingsError: String?
    @State private var recordingsSaved = false

    var body: some View {
        Form {
            Section("Create user") {
                LabeledField(label: "Email") {
                    TextField("", text: $email).keyboardType(.emailAddress).textContentType(.username)
                }
                LabeledField(label: "Password") {
                    SecureField("At least 8 characters", text: $password).textContentType(.newPassword)
                }
                Toggle("Admin", isOn: $isAdmin)
                FormMessage(error: createError)
            }
            .adminRow()
            Section {
                SaveButton(label: "Create", busy: creating, enabled: !email.isEmpty && password.count >= 8) {
                    Task { await createUser() }
                }
            }

            notificationsSection
            recordingsSection

            Section("Users & camera permissions") {
                if let usersError { FormMessage(error: usersError) }
                if let users {
                    if users.isEmpty { Text("No users yet.").foregroundStyle(Theme.muted) }
                } else if usersError == nil {
                    ProgressView("Loading…")
                }
            }
            .adminRow()
            ForEach(users ?? []) { access in userSection(access) }
        }
        .adminForm()
        .navigationTitle("Admin")
        .task {
            async let settings: Void = loadNotifications()
            async let recordingSettings: Void = loadRecordings()
            await loadUsers()
            await settings
            await recordingSettings
        }
        .refreshable { await loadUsers() }
        .confirmationDialog(
            "Delete \"\(pendingDelete?.user.email ?? "")\"?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let access = pendingDelete { Task { await deleteUser(access) } }
            }
        } message: {
            Text("This also revokes their sessions and camera permissions.")
        }
    }

    @ViewBuilder private var notificationsSection: some View {
        Section {
            if let current = notifications {
                Toggle("Notify on detections for all cameras", isOn: Binding(
                    get: { current.enabled },
                    set: {
                        notifications?.enabled = $0
                        notificationsSaved = false
                    }
                ))
                LabeledField(label: "Cooldown per camera (seconds)") {
                    TextField("60", text: $cooldown).keyboardType(.numberPad)
                }
                FormMessage(error: notificationsError, success: notificationsSaved ? "Saved." : nil)
                Button {
                    Task { await saveNotifications() }
                } label: {
                    if savingNotifications { ProgressView() } else { Text("Save") }
                }
                .disabled(savingNotifications)
            } else if let notificationsError {
                FormMessage(error: notificationsError)
            } else {
                ProgressView("Loading…")
            }
        } header: {
            Text("Detection notifications")
        } footer: {
            Text("""
                Notifies users who can view a camera when one of its selected detection classes is seen. \
                A camera's own setting (Camera settings → Detection notifications) overrides this switch.
                """)
        }
        .adminRow()
    }

    /// The desktop "Event recordings" card (minicam `recording-settings.js`), ADR 0005.
    @ViewBuilder private var recordingsSection: some View {
        Section {
            if let current = recordings {
                Toggle("Record a clip while a camera detects one of its classes", isOn: Binding(
                    get: { current.enabled },
                    set: {
                        recordings?.enabled = $0
                        recordingsSaved = false
                    }
                ))
                HStack {
                    Text("Storage limit").foregroundStyle(Theme.text)
                    Spacer()
                    Menu {
                        // Limits the disk can't hold stay visible but can't be chosen, like the web.
                        ForEach(current.options, id: \.gb) { option in
                            Button(option.available ? "\(option.gb) GB" : "\(option.gb) GB · Not enough space") {
                                recordings?.maxStorageGB = option.gb
                                recordingsSaved = false
                            }
                            .disabled(!option.available)
                        }
                    } label: {
                        Label("\(current.maxStorageGB) GB", systemImage: "chevron.up.chevron.down")
                            .labelStyle(.titleAndIcon)
                    }
                    .accessibilityLabel("Storage limit, \(current.maxStorageGB) GB")
                }
                Text("Recordings use \(formatStorageBytes(current.usedBytes)) · \(formatStorageBytes(current.freeBytes)) free on disk")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
                FormMessage(error: recordingsError, success: recordingsSaved ? "Saved." : nil)
                Button {
                    Task { await saveRecordings() }
                } label: {
                    if savingRecordings { ProgressView() } else { Text("Save") }
                }
                .disabled(savingRecordings)
            } else if let recordingsError {
                FormMessage(error: recordingsError)
            } else {
                ProgressView("Loading…")
            }
        } header: {
            Text("Event recordings")
        } footer: {
            Text("""
                Clips start about 3 seconds before a detection and stop about 5 seconds after the last one. \
                When the limit is reached, the oldest clips are deleted. Users watch them from ⋯ → Recordings.
                """)
        }
        .adminRow()
    }

    private func userSection(_ access: UserAccess) -> some View {
        Section {
            HStack(spacing: 8) {
                Text(access.user.email).foregroundStyle(Theme.text).lineLimit(1)
                if access.user.isAdmin { Badge(text: "admin") }
                Spacer()
                // The server refuses to delete yourself; don't offer it.
                if access.user.id != model.user?.id {
                    Button("Delete", role: .destructive) { pendingDelete = access }
                        .buttonStyle(.borderless)
                        .foregroundStyle(Theme.danger)
                }
            }
            Toggle("WAN access", isOn: Binding(
                get: { access.wanAccess },
                set: { on in Task { await setWAN(access, on) } }
            ))
            if cameras.isEmpty {
                Text("No cameras configured yet.").font(.caption).foregroundStyle(Theme.muted)
            } else {
                HStack {
                    Text("Camera")
                    Spacer()
                    Text("View").frame(width: 56)
                    Text("PTZ").frame(width: 56)
                }
                .font(.caption)
                .foregroundStyle(Theme.muted)
                ForEach(cameras, id: \.self) { camera in permissionRow(access, camera) }
            }
        } footer: {
            Text("WAN access: viewing streams and PTZ from outside the local network, through Cloudflare Access.")
        }
        .adminRow()
    }

    private func permissionRow(_ access: UserAccess, _ camera: String) -> some View {
        let permission = access.permissions.first { $0.cameraName == camera }
            ?? CameraPermission(userID: access.user.id, cameraName: camera, canView: false, canUsePTZ: false)
        return HStack {
            Text(camera).foregroundStyle(Theme.text).lineLimit(1)
            Spacer()
            checkbox(permission.canView) { on in
                var updated = permission
                updated.canView = on
                Task { await setPermission(access, updated) }
            }
            checkbox(permission.canUsePTZ) { on in
                var updated = permission
                updated.canUsePTZ = on
                Task { await setPermission(access, updated) }
            }
        }
    }

    private func checkbox(_ checked: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        Button {
            onChange(!checked)
        } label: {
            Image(systemName: checked ? "checkmark.square.fill" : "square")
                .font(.title3)
                .foregroundStyle(checked ? Theme.accent : Theme.border)
        }
        .buttonStyle(.borderless)
        .frame(width: 56)
    }

    // MARK: - Actions

    private func loadUsers() async {
        guard let api = model.api else { return }
        let result = await model.attempt({ usersError = $0 }, {
            async let names = api.adminCameras().map(\.name)
            let access = try await api.userAccess()
            return (access, try await names)
        })
        if let (access, names) = result {
            users = access
            cameras = names
            usersError = nil
        }
    }

    /// Updates one user's row right away; callers put the old value back if the request fails.
    private func update(_ id: Int, _ change: (inout UserAccess) -> Void) {
        guard let index = users?.firstIndex(where: { $0.id == id }) else { return }
        change(&users![index])
    }

    private func setWAN(_ access: UserAccess, _ on: Bool) async {
        guard let api = model.api else { return }
        usersError = nil
        update(access.id) { $0.wanAccess = on }
        if await model.attempt({ usersError = $0 }, { try await api.setWANAccess(userID: access.id, active: on) }) == nil {
            update(access.id) { $0.wanAccess = !on }
        }
    }

    private func setPermission(_ access: UserAccess, _ permission: CameraPermission) async {
        guard let api = model.api else { return }
        usersError = nil
        let previous = access.permissions
        update(access.id) {
            $0.permissions = previous.filter { $0.cameraName != permission.cameraName } + [permission]
        }
        if await model.attempt({ usersError = $0 }, { try await api.setPermission(permission) }) == nil {
            update(access.id) { $0.permissions = previous }
        }
    }

    private func deleteUser(_ access: UserAccess) async {
        guard let api = model.api else { return }
        usersError = nil
        if await model.attempt({ usersError = $0 }, { try await api.deleteUser(id: access.id) }) != nil {
            await loadUsers()
        }
    }

    private func createUser() async {
        guard let api = model.api else { return }
        createError = nil
        creating = true
        defer { creating = false }
        let created = await model.attempt({ createError = $0 }, {
            try await api.createUser(
                email: email.trimmingCharacters(in: .whitespaces), password: password, isAdmin: isAdmin
            )
        })
        if created != nil {
            email = ""
            password = ""
            isAdmin = false
            await loadUsers()
        }
    }

    private func loadNotifications() async {
        guard let api = model.api else { return }
        if let settings = await model.attempt({ notificationsError = $0 }, { try await api.notificationSettings() }) {
            notifications = settings
            cooldown = String(settings.cooldownSeconds)
        }
    }

    private func saveNotifications() async {
        guard let api = model.api, var settings = notifications else { return }
        notificationsError = nil
        guard let seconds = Int(cooldown.trimmingCharacters(in: .whitespaces)), (1...86400).contains(seconds) else {
            notificationsError = "Cooldown must be between 1 and 86400 seconds."
            return
        }
        settings.cooldownSeconds = seconds
        savingNotifications = true
        defer { savingNotifications = false }
        if let saved = await model.attempt({ notificationsError = $0 }, { try await api.saveNotificationSettings(settings) }) {
            notifications = saved
            cooldown = String(saved.cooldownSeconds)
            notificationsSaved = true
        }
    }

    private func loadRecordings() async {
        guard let api = model.api else { return }
        if let settings = await model.attempt({ recordingsError = $0 }, { try await api.recordingSettings() }) {
            recordings = settings
        }
    }

    private func saveRecordings() async {
        guard let api = model.api, let current = recordings else { return }
        recordingsError = nil
        savingRecordings = true
        defer { savingRecordings = false }
        if let saved = await model.attempt({ recordingsError = $0 }, {
            try await api.saveRecordingSettings(enabled: current.enabled, maxStorageGB: current.maxStorageGB)
        }) {
            recordings = saved
            recordingsSaved = true
        }
    }
}
