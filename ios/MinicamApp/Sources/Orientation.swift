import UIKit

/// Which orientations the app allows right now. Portrait and landscape normally; landscape only
/// while the camera is fullscreen (spec 004). Read by `AppDelegate`.
@MainActor
enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    /// Fullscreen: lock to landscape and rotate now. Leaving it: unlock and go back to portrait.
    static func landscape(_ enabled: Bool) {
        mask = enabled ? .landscape : .allButUpsideDown
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: enabled ? .landscape : .portrait)) { _ in }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}
