import MinicamKit
import SwiftUI

struct CamerasView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            List(model.cameras) { camera in
                NavigationLink(value: camera) {
                    HStack(spacing: 12) {
                        Image(systemName: "video")
                            .foregroundStyle(Theme.accent)
                        Text(camera.name)
                            .foregroundStyle(Theme.text)
                        Spacer()
                        if camera.canUsePTZ {
                            Text("PTZ")
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(Theme.muted)
                        }
                    }
                }
                .listRowBackground(Theme.surface)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .overlay {
                if model.cameras.isEmpty {
                    ContentUnavailableView(
                        "No cameras",
                        systemImage: "video.slash",
                        description: Text(model.errorMessage ?? "Ask an admin to grant you a camera.")
                    )
                }
            }
            .refreshable { await model.refresh() }
            .task { await model.refresh() }
            .navigationDestination(for: CameraSummary.self) { CameraView(camera: $0) }
            .navigationDestination(for: AdminRoute.self) { route in
                switch route {
                case .cameraSettings: CameraSettingsView()
                case .aiDetection: AIDetectionView()
                case .admin: AdminView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { BrandTitle().fixedSize().padding(.horizontal, 8) }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        // Admins only (ADR 0004); the server enforces it either way.
                        if model.user?.isAdmin == true {
                            NavigationLink(value: AdminRoute.cameraSettings) {
                                Label("Camera settings", systemImage: "video.badge.ellipsis")
                            }
                            NavigationLink(value: AdminRoute.aiDetection) {
                                Label("AI detection", systemImage: "sparkles")
                            }
                            NavigationLink(value: AdminRoute.admin) {
                                Label("Admin", systemImage: "person.2")
                            }
                            Divider()
                        }
                        Button("Log out", role: .destructive) { Task { await model.logout() } }
                    } label: {
                        Image(systemName: "ellipsis")
                            .accessibilityLabel("More options")
                    }
                    .accessibilityIdentifier("moreOptions")
                }
            }
            .toolbarBackground(Theme.bg, for: .navigationBar)
        }
    }
}
