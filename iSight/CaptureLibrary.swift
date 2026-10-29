import Foundation
import Combine

/// Keeps an app-local original even when a Files provider is disconnected.
@MainActor
final class CaptureLibrary: ObservableObject {
    @Published private(set) var files: [URL] = []
    @Published private(set) var folderName = "On My iPhone / iPad → i-Sight Med"
    @Published var message: String?

    private let defaults = UserDefaults.standard
    private let bookmarkKey = "captureFolderBookmark"
    nonisolated static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Captures", isDirectory: true)
    }

    init() {
        refresh()
        var stale = false
        if let data = defaults.data(forKey: bookmarkKey),
           let url = try? URL(resolvingBookmarkData: data, options: [],
                              relativeTo: nil, bookmarkDataIsStale: &stale) {
            folderName = url.lastPathComponent
        }
    }

    func selectFolder(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try url.bookmarkData(options: .minimalBookmark,
                                            includingResourceValuesForKeys: nil, relativeTo: nil)
            defaults.set(data, forKey: bookmarkKey)
            folderName = url.lastPathComponent
        } catch { message = "Could not remember this folder: \(error.localizedDescription)" }
    }

    func useLocalFolder() {
        defaults.removeObject(forKey: bookmarkKey)
        folderName = "On My iPhone / iPad → i-Sight Med"
    }

    func save(_ temporaryURL: URL) async {
        let bookmark = defaults.data(forKey: bookmarkKey)
        let result = await Task.detached(priority: .utility) { () -> String in
            let manager = FileManager.default
            let category = temporaryURL.pathExtension.lowercased() == "jpg" ? "Photos" : "Videos"
            let directory = Self.root.appendingPathComponent(category, isDirectory: true)
            let local = directory.appendingPathComponent(temporaryURL.lastPathComponent)
            do {
                try manager.createDirectory(at: directory, withIntermediateDirectories: true)
                try manager.moveItem(at: temporaryURL, to: local)
            } catch {
                // Leave the temporary file intact so it can be recovered if storage is full.
                return "Save failed. Capture remains at \(temporaryURL.path): \(error.localizedDescription)"
            }
            guard let bookmark else { return "Saved to Captures/\(category)." }
            do {
                var stale = false
                let folder = try URL(resolvingBookmarkData: bookmark, options: [],
                                     relativeTo: nil, bookmarkDataIsStale: &stale)
                let accessed = folder.startAccessingSecurityScopedResource()
                defer { if accessed { folder.stopAccessingSecurityScopedResource() } }
                let target = folder.appendingPathComponent(category, isDirectory: true)
                var coordinationError: NSError?
                var writeError: Error?
                NSFileCoordinator().coordinate(writingItemAt: target, options: .forMerging,
                                               error: &coordinationError) { coordinated in
                    do {
                        try manager.createDirectory(at: coordinated, withIntermediateDirectories: true)
                        try manager.copyItem(at: local, to: coordinated.appendingPathComponent(local.lastPathComponent))
                    } catch { writeError = error }
                }
                if let error = coordinationError ?? writeError as NSError? { throw error }
                return "Saved locally and copied to \(folder.lastPathComponent)."
            } catch {
                return "Saved locally. Folder copy failed; select the folder again or share from Captures. \(error.localizedDescription)"
            }
        }.value
        refresh()
        message = result
    }

    func refresh() {
        let manager = FileManager.default
        let entries = manager.enumerator(at: Self.root, includingPropertiesForKeys: [.isRegularFileKey])
        files = (entries?.allObjects as? [URL] ?? [])
            .filter { ["jpg", "mov"].contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
    }
}
