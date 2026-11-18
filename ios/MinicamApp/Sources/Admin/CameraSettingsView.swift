import MinicamKit
import SwiftUI

/// The desktop "Camera settings" modal (minicam `config.js`): list view; the form is pushed.
struct CameraSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var cameras: [CameraConfig]?
    @State private var error: String?
    @State private var pendingDelete: String?

    var body: some View {
        Form {
            Section {
                NavigationLink(value: CameraFormRoute(camera: nil)) {
                    Label(tr("Add camera"), systemImage: "plus")
                }
                .adminRow()
            }
            if let error {
                Section { FormMessage(error: error) }.adminRow()
            }
            Section {
                if let cameras {
                    if cameras.isEmpty {
                        Text(tr("No cameras configured yet.")).foregroundStyle(Theme.muted)
                    }
                    ForEach(cameras) { camera in
                        NavigationLink(value: CameraFormRoute(camera: camera)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(camera.name).foregroundStyle(Theme.text)
                                Text(camera.host).font(.caption.monospaced()).foregroundStyle(Theme.muted)
                            }
                        }
                        .swipeActions {
                            Button(tr("Delete"), role: .destructive) { pendingDelete = camera.name }
                        }
                        .contextMenu {
                            Button(tr("Delete"), systemImage: "trash", role: .destructive) { pendingDelete = camera.name }
                        }
                    }
                } else if error == nil {
                    ForEach(0..<3, id: \.self) { SkeletonRow(index: $0) }
                }
            } footer: {
                if cameras?.isEmpty == false { Text(tr("Swipe left on a camera to delete it.")) }
            }
            .adminRow()
        }
        .adminForm()
        .navigationTitle(tr("Camera settings"))
        .navigationDestination(for: CameraFormRoute.self) { CameraFormView(camera: $0.camera) }
        .task { await load() }
        .refreshable { await load() }
        .confirmationDialog(
            tr("Delete camera \"%s\"?", pendingDelete ?? ""),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button(tr("Delete"), role: .destructive) {
                if let name = pendingDelete { Task { await delete(name) } }
            }
        }
    }

    private func load() async {
        guard let api = model.api else { return }
        if let list = await model.attempt({ error = $0 }, { try await api.adminCameras() }) {
            cameras = list
            error = nil
        }
    }

    private func delete(_ name: String) async {
        guard let api = model.api else { return }
        error = nil
        if await model.attempt({ error = $0 }, { try await api.deleteCamera(name: name) }) != nil {
            await load()
            await model.refresh()
        }
    }
}

/// Add/edit form: every camera field, ONVIF discovery, detection classes, notifications mode.
struct CameraFormView: View {
    let camera: CameraConfig?

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var form = CameraConfig()
    @State private var rtspPort = ""
    @State private var onvifPort = ""
    @State private var channel = ""
    @State private var availableClasses: [String]?
    @State private var classesError: String?
    @State private var discovered: [DiscoveredCamera]?
    @State private var discovering = false
    @State private var discoverError: String?
    @State private var saving = false
    @State private var error: String?
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                LabeledField(label: tr("Name")) { TextField("my-camera", text: $form.name) }
                if camera != nil {
                    Text(tr("Rename requires all connected streams to switch to the new name."))
                        .font(.caption).foregroundStyle(Theme.muted)
                }
                LabeledField(label: tr("Host / IP")) {
                    HStack {
                        TextField("192.168.1.x", text: $form.host).keyboardType(.URL)
                        Button(tr("Search")) { Task { await discover() } }
                            .buttonStyle(.bordered)
                            .disabled(discovering)
                    }
                }
            }
            .adminRow()

            if discovering || discovered != nil || discoverError != nil {
                Section(tr("Found on network")) { discoveryResults }.adminRow()
            }

            Section {
                LabeledField(label: tr("RTSP port")) { TextField("554", text: $rtspPort).keyboardType(.numberPad) }
                LabeledField(label: tr("ONVIF port")) { TextField("8000", text: $onvifPort).keyboardType(.numberPad) }
                LabeledField(label: tr("Username")) { TextField("", text: $form.user).textContentType(.username) }
                LabeledField(label: tr("Password")) {
                    // Never prefilled (the server redacts it): blank keeps the current one.
                    PasswordField(placeholder: tr("Leave blank to keep current"), text: $form.password, contentType: nil)
                }
                LabeledField(label: tr("Channel")) { TextField("1", text: $channel).keyboardType(.numberPad) }
            } footer: {
                Text(tr("Leave the password blank to keep the existing one."))
            }
            .adminRow()

            Section(tr("Object detection")) { detectionClasses }.adminRow()

            Section {
                Picker(tr("Notifications"), selection: $form.notifications) {
                    Text(tr("Use global setting (Admin)")).tag(NotificationMode.inherit)
                    Text(tr("On for this camera")).tag(NotificationMode.on)
                    Text(tr("Off for this camera")).tag(NotificationMode.off)
                }
            } header: {
                Text(tr("Detection notifications"))
            } footer: {
                Text(tr("Notifies when any of the detection classes above is seen."))
            }
            .adminRow()

            Section {
                FormMessage(error: error)
                SaveButton(busy: saving) { Task { await save() } }
            }
        }
        .adminForm()
        .navigationTitle(camera == nil ? tr("Add camera") : tr("Edit camera"))
        .task { await load() }
    }

    @ViewBuilder private var discoveryResults: some View {
        if discovering {
            ProgressView(tr("Searching for cameras on the network…"))
        } else if let discoverError {
            Text(discoverError).foregroundStyle(Theme.danger)
        } else if let discovered, discovered.isEmpty {
            Text(tr("No cameras found on the network.")).foregroundStyle(Theme.muted)
        } else {
            ForEach(discovered ?? [], id: \.self) { found in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(found.host).font(.body.monospaced()).foregroundStyle(Theme.text)
                        Text(found.name).font(.caption).foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    Button(tr("Use")) { form.host = found.host }.buttonStyle(.bordered)
                }
            }
        }
    }

    @ViewBuilder private var detectionClasses: some View {
        if let classesError {
            Text(classesError).foregroundStyle(Theme.danger)
        } else if let availableClasses {
            if availableClasses.isEmpty {
                Text(tr("No detection backend configured on this device.")).foregroundStyle(Theme.muted)
            }
            if availableClasses.count > Self.maxVisibleClasses {
                // Show at most maxVisibleClasses rows; the rest scroll inside this one row.
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(availableClasses.enumerated()), id: \.element) { index, cls in
                            if index > 0 { Divider() }
                            classToggle(cls).frame(minHeight: Self.classRowHeight)
                        }
                    }
                }
                .frame(height: Self.classRowHeight * CGFloat(Self.maxVisibleClasses))
            } else {
                ForEach(availableClasses, id: \.self) { classToggle($0) }
            }
        } else {
            ForEach(0..<3, id: \.self) { ToggleSkeletonRow(index: $0) }
        }
    }

    private static let maxVisibleClasses = 6
    /// Height of a default inset-grouped form row.
    private static let classRowHeight: CGFloat = 44

    private func classToggle(_ cls: String) -> some View {
        Toggle(cls, isOn: Binding(
            get: { form.detectionClasses.contains(cls) },
            set: { on in
                form.detectionClasses.removeAll { $0 == cls }
                if on { form.detectionClasses.append(cls) }
            }
        ))
    }

    private func load() async {
        guard !loaded, let api = model.api else { return }
        loaded = true
        form = camera ?? CameraConfig()
        form.password = ""
        rtspPort = String(form.rtspPort)
        onvifPort = String(form.onvifPort)
        channel = String(form.channel)
        if let classes = await model.attempt({ _ in classesError = tr("Could not load detection classes.") }, {
            try await api.detectionClasses()
        }) {
            availableClasses = classes
        }
    }

    private func discover() async {
        guard let api = model.api else { return }
        discovering = true
        discoverError = nil
        discovered = await model.attempt({ _ in discoverError = tr("Discovery failed. Check network connectivity.") }, {
            try await api.discoverCameras()
        })
        discovering = false
    }

    private func save() async {
        guard let api = model.api else { return }
        var config = form
        config.name = config.name.trimmingCharacters(in: .whitespaces)
        config.host = config.host.trimmingCharacters(in: .whitespaces)
        config.user = config.user.trimmingCharacters(in: .whitespaces)
        guard !config.name.isEmpty, !config.host.isEmpty else {
            error = tr("Name and host are required.")
            return
        }
        guard let rtsp = Int(rtspPort.trimmingCharacters(in: .whitespaces)),
              let onvif = Int(onvifPort.trimmingCharacters(in: .whitespaces)), (1...65535).contains(rtsp),
              (1...65535).contains(onvif) else {
            error = tr("Ports must be between 1 and 65535.")
            return
        }
        guard let ch = Int(channel.trimmingCharacters(in: .whitespaces)), ch >= 1 else {
            error = tr("Channel must be 1 or more.")
            return
        }
        config.rtspPort = rtsp
        config.onvifPort = onvif
        config.channel = ch
        // With no backend there is nothing to pick from: keep the camera's classes as they are.
        if let availableClasses, !availableClasses.isEmpty {
            config.detectionClasses = availableClasses.filter(config.detectionClasses.contains)
        }
        error = nil
        saving = true
        defer { saving = false }
        if await model.attempt({ error = $0 }, { try await api.saveCamera(config, originalName: camera?.name) }) != nil {
            await model.refresh()
            dismiss()
        }
    }
}
