import SwiftUI

@main
struct MinicamApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                switch model.phase {
                case .signedOut:
                    LoginView()
                case .signedIn:
                    CamerasView()
                }
            }
            .environment(model)
            .preferredColorScheme(.dark)
            .tint(Theme.accent)
        }
    }
}
