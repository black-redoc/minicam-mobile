import MinicamKit
import SwiftUI
import UIKit

struct CameraView: View {
    let camera: CameraSummary

    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var frame: UIImage?
    @State private var status: StreamStatus = .connecting
    @State private var isFullscreen = false
    @State private var showPTZ = false
    // Shared by both layouts, so entering or leaving fullscreen keeps the zoom (spec 008 AC3).
    @State private var zoom = VideoZoom()
    // Spec 009: audio is off every time the camera opens; fMP4 until it fails once (then MJPEG).
    @State private var audioOn = false
    @State private var fmp4Failed = false
    @State private var choice: StreamChoice?
    @State private var audioAvailable = false
    @State private var detections: Detections?
    @State private var player = LivePlayer()

    /// Restarts the stream when it changes: foreground, audio toggled, fallback to MJPEG.
    private struct StreamKey: Hashable {
        let active: Bool
        let audio: Bool
        let fmp4Failed: Bool
    }

    var body: some View {
        // One container for both layouts: the stream task below lives on it, so toggling
        // fullscreen does not reconnect.
        ZStack {
            if isFullscreen { fullscreen } else { regular }
        }
        .navigationTitle(camera.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { StatusBadge(status: status) } }
        .toolbar(isFullscreen ? .hidden : .visible, for: .navigationBar)
        .statusBarHidden(isFullscreen)
        .persistentSystemOverlays(isFullscreen ? .hidden : .automatic)
        .onChange(of: isFullscreen) { _, on in OrientationLock.landscape(on) }
        .onDisappear {
            if isFullscreen { OrientationLock.landscape(false) }
            player.stop()
        }
        // Streaming only while visible and in the foreground, like the desktop's
        // visibilitychange handling: a background app costs the Pi nothing.
        .task(id: StreamKey(active: scenePhase == .active, audio: audioOn, fmp4Failed: fmp4Failed)) {
            guard scenePhase == .active, let api = model.api else { return }
            await stream(api: api)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                player.catchUp()
            }
        }
        .onChange(of: player.failed) { _, failed in if failed { fmp4Failed = true } }
    }

    /// Only offered when the server says this user may listen and the device can play it.
    @ViewBuilder
    private func speakerButton(size: CGFloat) -> some View {
        if camera.canListen, audioAvailable {
            glassButton(
                audioOn ? "Turn audio off" : "Turn audio on",
                systemImage: audioOn ? "speaker.wave.2.fill" : "speaker.slash.fill", size: size
            ) { audioOn.toggle() }
        }
    }

    private var regular: some View {
        ScrollView {
            VStack(spacing: 16) {
                videoFeed
                    .zoomPan($zoom)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.radiusLg))
                    .overlay(alignment: .topTrailing) { ZoomButtons(zoom: $zoom, size: 40).padding(10) }
                    .overlay(alignment: .bottomLeading) { speakerButton(size: 40).padding(10) }
                    .overlay(alignment: .bottomTrailing) {
                        glassButton("Enter fullscreen", systemImage: "arrow.up.left.and.arrow.down.right", size: 40) {
                            isFullscreen = true
                        }
                        .padding(10)
                    }
                if camera.canUsePTZ, let api = model.api {
                    PTZPad(api: api, camera: camera.name) { model.handle($0) }
                        .frame(maxWidth: .infinity)
                        .card()
                }
            }
            .padding(16)
        }
        .background(Theme.bg.ignoresSafeArea())
    }

    /// Spec 004: the feed over the whole screen with floating glass controls.
    private var fullscreen: some View {
        ZStack {
            videoFeed.zoomPan($zoom).ignoresSafeArea()
            VStack {
                HStack(spacing: 12) {
                    glassButton("Exit fullscreen", systemImage: "arrow.down.right.and.arrow.up.left") {
                        isFullscreen = false
                    }
                    StatusBadge(status: status)
                    Spacer()
                    if camera.canUsePTZ, !showPTZ {
                        glassButton("Show PTZ controls", systemImage: "gamecontroller") {
                            withAnimation(.snappy) { showPTZ = true }
                        }
                    }
                }
                Spacer()
                HStack(alignment: .bottom) {
                    speakerButton(size: 44)
                    Spacer()
                    ZoomButtons(zoom: $zoom)
                }
            }
            .padding(16)
            if camera.canUsePTZ, showPTZ, let api = model.api {
                ptzPanel(api: api)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, 16)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .background(Color.black.ignoresSafeArea())
    }

    /// The PTZ pad inside a glass circle, with the (x) centred on the circle's edge at its
    /// top-right (45°): half over the circle, half outside it, drawn on top.
    private func ptzPanel(api: MinicamAPI) -> some View {
        let diameter = Theme.ptzSize * 3 + 12 + 32
        let closeSize: CGFloat = 30
        // Distance from the bounding box's top-right corner to the 45° point on the circle, per axis.
        let inset = diameter / 2 * (1 - 0.7071)
        return PTZPad(api: api, camera: camera.name, glass: true) { model.handle($0) }
            .frame(width: diameter, height: diameter)
            .glassSurface(in: Circle())
            .overlay(alignment: .topTrailing) {
                glassButton("Hide PTZ controls", systemImage: "xmark", size: closeSize) {
                    withAnimation(.snappy) { showPTZ = false }
                }
                .background(Theme.bg.opacity(0.6), in: Circle())
                .offset(x: closeSize / 2 - inset, y: inset - closeSize / 2)
            }
    }

    private func glassButton(
        _ label: String, systemImage: String, size: CGFloat = 44, action: @escaping () -> Void
    ) -> some View {
        GlassIconButton(label: label, systemImage: systemImage, size: size, action: action)
    }

    private var isFMP4: Bool { choice?.format == .fmp4 }

    /// Frame fitted to the container (`object-fit: contain`): a placeholder until the first
    /// frame, then the dimmed last frame with a spinner while reconnecting (spec 007 AC4). With
    /// fMP4, the live player instead of the JPEG frame, with the detection boxes over it.
    private var videoFeed: some View {
        ZStack {
            Color.black
            if isFMP4 {
                LiveVideoView(player: player)
                if let detections { DetectionOverlay(detections: detections) }
            } else if let frame {
                Image(uiImage: frame)
                    .resizable()
                    .scaledToFit()
            }
            if !(isFMP4 ? player.hasPicture : frame != nil) {
                SkeletonBlock(width: nil, height: nil, cornerRadius: 0).skeletonGroup()
            } else if status != .connected {
                ZStack {
                    Color.black.opacity(0.72)
                    ProgressView().tint(Theme.accent)
                }
            }
        }
        .accessibilityLabel("Live camera feed")
    }

    private func stream(api: MinicamAPI) async {
        let client = StreamClient(
            api: api, transport: model.transport, camera: camera.name,
            preferFMP4: !fmp4Failed, audio: audioOn, canPlay: canDecode
        )
        defer { player.stop() }
        do {
            for try await event in client.events() {
                switch event {
                case let .status(newStatus):
                    status = newStatus
                case let .session(newChoice, available):
                    choice = newChoice
                    audioAvailable = available
                    if newChoice.format == .mjpeg { player.stop() }
                case let .frame(jpeg):
                    if let image = UIImage(data: jpeg) { frame = image }
                case let .initSegment(_, data):
                    do {
                        try player.start(initSegment: data, audio: choice?.audio == true)
                    } catch {
                        fmp4Failed = true
                    }
                case let .fragment(data):
                    player.append(data)
                case let .detections(newDetections):
                    detections = newDetections
                }
            }
        } catch {
            status = .disconnected
            model.handle(error)
        }
    }
}

/// `.badge--connected/disconnected/connecting` from main.css.
struct StatusBadge: View {
    let status: StreamStatus

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).font(.caption2.weight(.medium))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 10)
        .padding(.vertical, 3)
        .background(color.opacity(0.08))
        .overlay(Capsule().stroke(color.opacity(0.25)))
        .clipShape(Capsule())
    }

    private var color: Color {
        switch status {
        case .connected: Theme.success
        case .disconnected: Theme.danger
        case .connecting: Theme.muted
        }
    }

    private var label: String {
        switch status {
        case .connected: "Live"
        case .disconnected: "Disconnected"
        case .connecting: "Connecting…"
        }
    }
}

/// Round glass button floating over video (spec 004), also used by the recordings player.
struct GlassIconButton: View {
    let label: String
    let systemImage: String
    var size: CGFloat = 44
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .glassSurface(in: Circle())
                .opacity(isEnabled ? 1 : 0.4)
        }
        .accessibilityLabel(label)
    }
}

/// Glass "Zoom in" / "Zoom out" buttons for digital zoom over the video (spec 008).
struct ZoomButtons: View {
    @Binding var zoom: VideoZoom
    var size: CGFloat = 44

    var body: some View {
        VStack(spacing: 8) {
            GlassIconButton(label: "Zoom in", systemImage: "plus", size: size) { zoom = zoom.zoomedIn() }
                .disabled(!zoom.canZoomIn)
            GlassIconButton(label: "Zoom out", systemImage: "minus", size: size) { zoom = zoom.zoomedOut() }
                .disabled(!zoom.canZoomOut)
        }
    }
}

extension View {
    /// Scales and pans the content by `zoom`, clipped to its frame; while zoomed, dragging the
    /// content pans it (spec 008 AC2).
    func zoomPan(_ zoom: Binding<VideoZoom>) -> some View {
        modifier(ZoomPan(zoom: zoom))
    }
}

private struct ZoomPan: ViewModifier {
    @Binding var zoom: VideoZoom
    @State private var lastTranslation: CGSize = .zero

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let size = geometry.size
            content
                .frame(width: size.width, height: size.height)
                .scaleEffect(zoom.scale)
                .offset(x: zoom.panX * size.width, y: zoom.panY * size.height)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 10)
                        .onChanged { drag in
                            guard size.width > 0, size.height > 0 else { return }
                            let dx = drag.translation.width - lastTranslation.width
                            let dy = drag.translation.height - lastTranslation.height
                            lastTranslation = drag.translation
                            zoom = zoom.panned(dx: dx / size.width, dy: dy / size.height)
                        }
                        .onEnded { _ in lastTranslation = .zero },
                    including: zoom.isZoomed ? .all : .subviews
                )
        }
        .clipped()
    }
}
