import SwiftUI

@main
struct ISightApp: App {
    @UIApplicationDelegateAdaptor(OrientationDelegate.self) private var delegate
    @StateObject private var library = CaptureLibrary()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(library)
                .tint(Color(red: 55/255, green: 0, blue: 179/255))
        }
    }
}

/// Android uses sensorLandscape for Live View. Keep the iOS controls in the
/// same landscape layout while AVFoundation independently levels the image.
@MainActor
enum AppOrientation {
    static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    static func setLiveView(_ live: Bool) {
        mask = live ? .landscape : .allButUpsideDown
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows {
                var controller = window.rootViewController
                while let current = controller {
                    current.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = current.presentedViewController
                }
            }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        }
    }
}

final class OrientationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppOrientation.mask
    }
}
