import AVFoundation
import MinicamKit
import SwiftUI

/// Pushes the Recordings page from the camera list menu; for every user (ADR 0005).
struct RecordingsRoute: Hashable {}

/// The desktop Recordings page (minicam `recordings.html`): clips by day, player, download, delete.
struct RecordingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
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
    @State private var playback = ClipPlayback()
    @State private var playError: String?
    @State private var isFullscreen = false

    /// AC11: opening or closing the player. The player fades while everything below it slides,
    /// both on this one curve, so they start and end together.
    private static let playerAnimation = Animation.easeInOut(duration: 0.5)

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
        .onChange(of: scenePhase) { _, phase in if phase != .active { playback.pause() } }
        .onDisappear {
            playback.pause()
            if isFullscreen { OrientationLock.landscape(false) }
        }
    }

    /// Player, clip actions, camera filter and the list title stay fixed; only the clips scroll.
    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                if let selected {
                    playerCard(selected).transition(.opacity)
                }
                HStack(spacing: 16) {
                    // The clip's actions fade in with the player, pushing the filter along (AC11).
                    if let selected {
                        clipActions(selected).transition(.opacity)
                    }
                    Picker("Camera", selection: $camera) {
                        Text("All cameras").tag(String?.none)
                        ForEach(model.cameras) { Text($0.name).tag(String?.some($0.name)) }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    Spacer(minLength: 0)
                }
                Text("Event recordings".uppercased())
                    .font(.caption2.weight(.semibold))
                    .tracking(1.5)
                    .foregroundStyle(Theme.muted)
                    .padding(.top, 4)
            }
            .padding([.horizontal, .top], 16)
            .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let error { FormMessage(error: error) }
                    if let items, items.isEmpty {
                        Text(camera == nil
                            ? "No recordings yet. Clips appear here when a camera detects one of its classes."
                            : "No recordings for this camera yet.")
                            .font(.callout)
                            .foregroundStyle(Theme.muted)
                    } else if items == nil, error == nil {
                        ProgressView("Loading…").frame(maxWidth: .infinity)
                    }

                    ForEach(recordingDays(items ?? [])) { day in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(day.label).font(.caption.weight(.medium)).foregroundStyle(Theme.muted)
                            VStack(spacing: 0) {
                                ForEach(Array(day.recordings.enumerated()), id: \.element.id) { index, recording in
                                    if index > 0 { Divider().overlay(Theme.border) }
                                    Button { open(recording) } label: {
                                        ClipRow(recording: recording, selected: recording == selected)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .card()
                        }
                    }

                    if nextBefore != nil {
                        Button {
                            Task { await load(reset: false) }
                        } label: {
                            if loading { ProgressView() } else { Text("Load more").font(.callout.weight(.medium)) }
                        }
                        .frame(maxWidth: .infinity)
                        .disabled(loading)
                    }
                }
                .padding([.horizontal, .bottom], 16)
                .padding(.top, 8)
            }
            .refreshable { await load(reset: true) }
        }
        .background(Theme.bg.ignoresSafeArea())
        .task(id: camera) { await load(reset: true) }
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

    private func playerCard(_ recording: Recording) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ClipPlayerView(playback: playback, isFullscreen: false) { isFullscreen = $0 }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: Theme.radiusLg))
            if let playError { FormMessage(error: playError) }
            clipDetails(recording)
        }
    }

    /// AC9: the clip over the whole screen, landscape, like the live view (spec 004).
    private var fullscreenPlayer: some View {
        ClipPlayerView(playback: playback, isFullscreen: true) { isFullscreen = $0 }
            .background(Color.black.ignoresSafeArea())
    }

    private func clipDetails(_ recording: Recording) -> some View {
        let details: [String?] = [
            "\(recordingDays([recording])[0].label), \(recording.started.formatted(date: .omitted, time: .standard))",
            formatClipDuration(recording.durationSeconds),
            formatClipSize(recording.sizeBytes),
            recording.classes.isEmpty ? nil : recording.classes.joined(separator: ", "),
        ]
        return VStack(alignment: .leading, spacing: 4) {
            Text(recording.cameraName).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            Text(details.compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(Theme.muted)
        }
    }

    private func clipActions(_ recording: Recording) -> some View {
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

    /// Opens a clip. The player is in the fixed top part, so it's always in view. From a closed
    /// player the opening animates (AC11); switching clips just swaps the video.
    private func open(_ recording: Recording) {
        if selected == nil {
            withAnimation(Self.playerAnimation) { selected = recording }
        } else {
            selected = recording
        }
    }

    /// Streams the clip with the same auth headers as every other request (ADR 0005), seeking
    /// with HTTP Range.
    private func play(_ recording: Recording?) async {
        playError = nil
        guard let recording, let api = model.api else {
            isFullscreen = false
            playback.load(nil)
            return
        }
        let request = api.recordingVideoRequest(id: recording.id)
        guard let url = request.url else { return }
        // Not a documented key, but the only way to give AVURLAsset headers (ADR 0005).
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": request.allHTTPHeaderFields ?? [:]])
        let item = AVPlayerItem(asset: asset)
        playback.load(item)
        for await status in item.publisher(for: \.status).values where status == .failed {
            playError = "Can't play this clip."
        }
    }

    private func delete(_ recording: Recording) async {
        guard let api = model.api else { return }
        if await model.attempt({ error = $0 }, { try await api.deleteRecording(id: recording.id) }) != nil {
            withAnimation(Self.playerAnimation) {
                items?.removeAll { $0.id == recording.id }
                if selected == recording { selected = nil }
            }
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
        .padding(.vertical, 10)
        .padding(.horizontal, 4)
        .background(selected ? Theme.accent.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: Theme.radiusSm))
        .contentShape(Rectangle())
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
