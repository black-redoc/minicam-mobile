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
        .onDisappear { if isFullscreen { OrientationLock.landscape(false) } }
        // Streaming only while visible and in the foreground, like the desktop's
        // visibilitychange handling: a background app costs the Pi nothing.
        .task(id: scenePhase == .active) {
            guard scenePhase == .active, let api = model.api else { return }
            await stream(api: api)
        }
    }

    private var regular: some View {
        ScrollView {
            VStack(spacing: 16) {
                videoFeed
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.radiusLg))
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
            videoFeed.ignoresSafeArea()
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

    /// Frame fitted to the container (`object-fit: contain`) with a spinner while not live.
    private var videoFeed: some View {
        ZStack {
            Color.black
            if let frame {
                Image(uiImage: frame)
                    .resizable()
                    .scaledToFit()
            }
            if status != .connected {
                ZStack {
                    Color.black.opacity(0.72)
                    ProgressView().tint(Theme.accent)
                }
            }
        }
        .accessibilityLabel("Live camera feed")
    }

    private func stream(api: MinicamAPI) async {
        let client = StreamClient(api: api, transport: model.transport, camera: camera.name)
        do {
            for try await event in client.events() {
                switch event {
                case let .status(newStatus):
                    status = newStatus
                case let .frame(jpeg):
                    if let image = UIImage(data: jpeg) { frame = image }
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

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .glassSurface(in: Circle())
        }
        .accessibilityLabel(label)
    }
}
