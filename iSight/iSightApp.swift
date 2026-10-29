import SwiftUI

@main
struct ISightApp: App {
    @StateObject private var library = CaptureLibrary()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(library)
                .tint(.teal)
        }
    }
}
