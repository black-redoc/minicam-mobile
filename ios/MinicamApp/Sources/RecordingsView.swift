import AVKit
import MinicamKit
import SwiftUI

/// Pushes the Recordings page from the camera list menu; for every user (ADR 0005).
struct RecordingsRoute: Hashable {}

/// The desktop Recordings page (minicam `recordings.html`): clips by day, player, download, delete.
struct RecordingsView: View {
    @Environment(AppModel.self) private var model
    @State private var camera: String?
    @State private var items: [Recording]?
    @State private var nextBefore: Int?
    @State private var loading = false
    @State private var error: String?
    @State private var selected: Recording?
    @State private var pendingDelete: Recording?
    @State private var downloading = false
    @State private var sharedFile: SharedFile?
    // One player for both layouts, so entering or leaving fullscreen doesn't restart the clip.
    @State private var player = AVPlayer()
    @State private var playError: String?
    @State private var isFullscreen = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if isFullscreen { fullscreenPlayer } else { list }
        }
        .navigationTitle("Recordings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(isFullscreen ? .hidden : .visible, for: .navigationBar)
        .statusBarHidden(isFullscreen)
        .persistentSystemOverlays(isFullscreen ? .hidden : .automatic)
        .onChange(of: isFullscreen) { _, on in OrientationLock.landscape(on) }
        .task(id: selected?.id) { await play(selected) }
        // Never keep playing (and downloading) in the background.
        .onChange(of: scenePhase) { _, phase in if phase != .active { player.pause() } }
        .onDisappear {
            player.pause()
            if isFullscreen { OrientationLock.landscape(false) }
        }
    }

    private var list: some View {
        List {
            if let selected {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        VideoPlayer(player: player)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .background(Color.black)
                            .overlay(alignment: .topTrailing) {
                                GlassIconButton(
                                    label: "Enter fullscreen", systemImage: "arrow.up.left.and.arrow.down.right", size: 40
                                ) { isFullscreen = true }
                                .padding(10)
                            }
                        if let playError { FormMessage(error: playError).padding(.horizontal) }
                    }
                    .listRowInsets(EdgeInsets())
                    clipDetails(selected)
                }
                .adminRow()
            }

            Section {
                Picker("Camera", selection: $camera) {
                    Text("All cameras").tag(String?.none)
                    ForEach(model.cameras) { Text($0.name).tag(String?.some($0.name)) }
                }
                if let error { FormMessage(error: error) }
                if let items, items.isEmpty {
                    Text(camera == nil
                        ? "No recordings yet. Clips appear here when a camera detects one of its classes."
                        : "No recordings for this camera yet.")
                        .font(.callout)
                        .foregroundStyle(Theme.muted)
                } else if items == nil, error == nil {
                    ProgressView("Loading…")
                }
            } header: {
                Text("Event recordings")
            }
            .adminRow()

            ForEach(recordingDays(items ?? [])) { day in
                Section(day.label) {
                    ForEach(day.recordings) { recording in
                        Button { selected = recording } label: { ClipRow(recording: recording, selected: recording == selected) }
                            .listRowBackground(recording == selected ? Theme.accent.opacity(0.1) : Theme.surface)
                    }
                }
            }

            if nextBefore != nil {
                Section {
                    Button {
                        Task { await load(reset: false) }
                    } label: {
                        if loading { ProgressView() } else { Text("Load more") }
                    }
                    .frame(maxWidth: .infinity)
                    .disabled(loading)
                }
                .adminRow()
            }
        }
        .adminForm()
        .task(id: camera) { await load(reset: true) }
        .refreshable { await load(reset: true) }
        .confirmationDialog(
            "Delete recording?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let recording = pendingDelete { Task { await delete(recording) } }
            }
        } message: {
            Text("The clip is deleted from this device for every user.")
        }
        // The downloaded MP4 lives in the temp dir only while the share sheet is open (SECURITY.md).
        .sheet(item: $sharedFile) { file in
            ShareSheet(url: file.url).onDisappear { file.remove() }
        }
    }

    /// AC9: the clip over the whole screen, landscape, like the live view (spec 004).
    private var fullscreenPlayer: some View {
        ZStack(alignment: .topLeading) {
            VideoPlayer(player: player).ignoresSafeArea()
            GlassIconButton(label: "Exit fullscreen", systemImage: "arrow.down.right.and.arrow.up.left") {
                isFullscreen = false
            }
            .padding(16)
        }
        .background(Color.black.ignoresSafeArea())
    }

    private func clipDetails(_ recording: Recording) -> some View {
        let details: [String?] = [
            "\(recordingDays([recording])[0].label), \(recording.started.formatted(date: .omitted, time: .standard))",
            formatClipDuration(recording.durationSeconds),
            formatClipSize(recording.sizeBytes),
            recording.classes.isEmpty ? nil : recording.classes.joined(separator: ", "),
        ]
        return VStack(alignment: .leading, spacing: 8) {
            Text(recording.cameraName).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            Text(details.compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(Theme.muted)
            HStack(spacing: 16) {
                Button {
                    Task { await download(recording) }
                } label: {
                    if downloading { ProgressView() } else { Label("Download", systemImage: "arrow.down.to.line") }
                }
                .disabled(downloading)
                // Admins only; the server's `require_admin` enforces it either way.
                if model.user?.isAdmin == true {
                    Button(role: .destructive) { pendingDelete = recording } label: {
                        Label("Delete", systemImage: "trash")
                    }
                    .foregroundStyle(Theme.danger)
                }
            }
            .buttonStyle(.borderless)
            .font(.callout)
        }
    }

    // MARK: - Actions

    private func load(reset: Bool) async {
        guard let api = model.api, !loading else { return }
        loading = true
        defer { loading = false }
        let page = await model.attempt({ error = $0 }, {
            try await api.recordings(camera: camera, before: reset ? nil : nextBefore)
        })
        if let page {
            items = reset ? page.items : (items ?? []) + page.items
            nextBefore = page.nextBefore
            error = nil
        }
    }

    /// Streams the clip with the same auth headers as every other request (ADR 0005), seeking
    /// with HTTP Range.
    private func play(_ recording: Recording?) async {
        playError = nil
        guard let recording, let api = model.api else {
            isFullscreen = false
            player.replaceCurrentItem(with: nil)
            return
        }
        let request = api.recordingVideoRequest(id: recording.id)
        guard let url = request.url else { return }
        // Not a documented key, but the only way to give AVURLAsset headers (ADR 0005).
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": request.allHTTPHeaderFields ?? [:]])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        player.play()
        for await status in item.publisher(for: \.status).values where status == .failed {
            playError = "Can't play this clip."
        }
    }

    private func delete(_ recording: Recording) async {
        guard let api = model.api else { return }
        if await model.attempt({ error = $0 }, { try await api.deleteRecording(id: recording.id) }) != nil {
            items?.removeAll { $0.id == recording.id }
            if selected == recording { selected = nil }
        }
    }

    private func download(_ recording: Recording) async {
        guard let api = model.api else { return }
        downloading = true
        defer { downloading = false }
        if let url = await model.attempt({ error = $0 }, { try await api.downloadRecording(recording) }) {
            sharedFile = SharedFile(url: url)
        }
    }
}

private struct ClipRow: View {
    let recording: Recording
    let selected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(recording.started.formatted(date: .omitted, time: .standard))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(selected ? Theme.accentHover : Theme.text)
            VStack(alignment: .leading, spacing: 4) {
                Text(recording.cameraName).font(.subheadline).foregroundStyle(Theme.text).lineLimit(1)
                if !recording.classes.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(recording.classes, id: \.self) { cls in
                            Text(cls)
                                .font(.caption2)
                                .foregroundStyle(Theme.text.opacity(0.85))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Theme.border, in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
            }
            Spacer()
            VStack(alignment: .trailing) {
                Text(formatClipDuration(recording.durationSeconds))
                Text(formatClipSize(recording.sizeBytes)).opacity(0.7)
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(Theme.muted)
        }
    }
}

/// A downloaded clip waiting in the temp dir for the share sheet.
private struct SharedFile: Identifiable {
    let url: URL
    var id: URL { url }

    func remove() { try? FileManager.default.removeItem(at: url) }
}

/// `UIActivityViewController`: Save to Files, Save Video, AirDrop…
private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
