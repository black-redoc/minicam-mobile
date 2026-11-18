import MinicamKit
import SwiftUI

@main
struct MinicamApp: App {
    @UIApplicationDelegateAdaptor private var appDelegate: AppDelegate
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
            // Dates follow the app language too (docs/adr/0017).
            .environment(\.locale, I18n.locale)
            .environment(model)
            // The dashboard's QR (docs/adr/0011); the Access callback uses its own session.
            .onOpenURL { url in
                if url.scheme == "minicam", url.host == "pair" { model.openPairLink(url) }
            }
            .preferredColorScheme(.dark)
            .tint(Theme.accent)
        }
    }
}
