import MinicamKit
import SwiftUI
import UIKit

struct CameraView: View {
    let camera: CameraSummary

    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var frame: UIImage?
    @State private var status: StreamStatus = .connecting

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                videoContainer
                if camera.canUsePTZ, let api = model.api {
                    PTZPad(api: api, camera: camera.name) { model.handle($0) }
                        .frame(maxWidth: .infinity)
                        .card()
                }
            }
            .padding(16)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle(camera.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { StatusBadge(status: status) } }
        // Streaming only while visible and in the foreground, like the desktop's
        // visibilitychange handling: a background app costs the Pi nothing.
        .task(id: scenePhase == .active) {
            guard scenePhase == .active, let api = model.api else { return }
            await stream(api: api)
        }
    }

    private var videoContainer: some View {
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
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radiusLg))
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
