import MinicamKit
import SwiftUI

struct CamerasView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.path) {
            List {
                NetworkSwitch()
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                // Admins: a newer minicam is out (docs/adr/0017); the update itself is in Settings.
                if let latest = availableUpdate {
                    NavigationLink(value: SettingsRoute()) {
                        HStack {
                            Text(tr("minicam %s is available", latest)).foregroundStyle(Theme.text)
                            Spacer()
                            Text(tr("Update")).fontWeight(.semibold).foregroundStyle(Theme.success)
                        }
                    }
                    .listRowBackground(Theme.success.opacity(0.12))
                }
                if showsSkeleton {
                    ForEach([140, 96, 120, 80], id: \.self) { width in
                        HStack(spacing: 12) {
                            SkeletonBlock(width: 22, height: 16)
                            SkeletonBlock(width: CGFloat(width), height: 16)
                        }
                        .skeletonGroup()
                        .listRowBackground(Theme.surface)
                    }
                }
                ForEach(model.cameras) { camera in
                    NavigationLink(value: camera) {
                        HStack(spacing: 12) {
                            Image(systemName: "video")
                                .foregroundStyle(Theme.accent)
                            Text(camera.name)
                                .foregroundStyle(Theme.text)
                            Spacer()
                        }
                    }
                    .listRowBackground(Theme.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .overlay {
                if model.cameras.isEmpty, !showsSkeleton {
                    ContentUnavailableView(
                        tr("No cameras"),
                        systemImage: "video.slash",
                        description: Text(model.errorMessage ?? tr("Ask an admin to grant you a camera."))
                    )
                }
            }
            .refreshable { await model.refresh() }
            .task { await model.refresh() }
            .navigationDestination(for: CameraSummary.self) { CameraView(camera: $0) }
            .navigationDestination(for: RecordingsRoute.self) { _ in RecordingsView() }
            .navigationDestination(for: SettingsRoute.self) { _ in SettingsView() }
            .navigationDestination(for: AdminRoute.self) { route in
                switch route {
                case .cameraSettings: CameraSettingsView()
                case .aiDetection: AIDetectionView()
                case .admin: AdminView()
                case .centralDashboard: CentralDashboardView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { BrandTitle().fixedSize().padding(.horizontal, 8) }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: SettingsRoute()) {
                        Image(systemName: "gearshape")
                            .overlay(alignment: .topTrailing) {
                                if availableUpdate != nil {
                                    Circle().fill(Theme.success).frame(width: 8, height: 8).offset(x: 3, y: -3)
                                }
                            }
                    }
                    .accessibilityLabel(availableUpdate == nil ? tr("Settings") : tr("Settings, update available"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        NavigationLink(value: RecordingsRoute()) {
                            Label(tr("Recordings"), systemImage: "video.badge.checkmark")
                        }
                        // Admins only (ADR 0004); the server enforces it either way.
                        if model.user?.isAdmin == true {
                            NavigationLink(value: AdminRoute.cameraSettings) {
                                Label(tr("Camera settings"), systemImage: "video.badge.ellipsis")
                            }
                            NavigationLink(value: AdminRoute.aiDetection) {
                                Label(tr("AI detection"), systemImage: "sparkles")
                            }
                            NavigationLink(value: AdminRoute.admin) {
                                Label(tr("Admin"), systemImage: "person.2")
                            }
                            NavigationLink(value: AdminRoute.centralDashboard) {
                                Label(tr("Connect to dashboard"), systemImage: "building.2")
                            }
                            Divider()
                        }
                        Button(tr("Log out"), role: .destructive) { Task { await model.logout() } }
                    } label: {
                        Image(systemName: "ellipsis")
                            .accessibilityLabel(tr("More options"))
                    }
                    .accessibilityIdentifier("moreOptions")
                }
            }
            .toolbarBackground(Theme.bg, for: .navigationBar)
        }
    }

    private var availableUpdate: String? {
        guard let update = model.systemUpdate, update.updateAvailable else { return nil }
        return update.latest
    }

    /// Placeholder rows until the first camera request finishes (spec 007 AC1).
    private var showsSkeleton: Bool {
        model.cameras.isEmpty && !model.camerasLoaded && model.errorMessage == nil
    }
}
