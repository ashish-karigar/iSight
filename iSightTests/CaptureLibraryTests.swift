import XCTest
@testable import iSight

final class CaptureLibraryTests: XCTestCase {
    @MainActor
    func testCaptureIsSavedLocallyAndListed() async throws {
        let library = CaptureLibrary()
        library.useLocalFolder()
        let input = FileManager.default.temporaryDirectory.appendingPathComponent("test-\(UUID()).jpg")
        let data = Data([0x01, 0x02, 0x03])
        try data.write(to: input)
        let saved = CaptureLibrary.root.appendingPathComponent("Photos").appendingPathComponent(input.lastPathComponent)
        defer { try? FileManager.default.removeItem(at: saved) }
        await library.save(input)
        XCTAssertEqual(try Data(contentsOf: saved), data)
        XCTAssertFalse(FileManager.default.fileExists(atPath: input.path))
        XCTAssertTrue(library.files.contains(saved))
    }

    @MainActor
    func testSelectedFolderReceivesCopyAndOriginalIsRetained() async throws {
        let library = CaptureLibrary()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        library.selectFolder(folder)
        let input = FileManager.default.temporaryDirectory.appendingPathComponent("test-\(UUID()).mov")
        let data = Data([0x04, 0x05])
        try data.write(to: input)
        let local = CaptureLibrary.root.appendingPathComponent("Videos").appendingPathComponent(input.lastPathComponent)
        defer {
            library.useLocalFolder()
            try? FileManager.default.removeItem(at: local)
            try? FileManager.default.removeItem(at: folder)
        }
        await library.save(input)
        XCTAssertEqual(try Data(contentsOf: local), data)
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("Videos").appendingPathComponent(input.lastPathComponent)), data)
    }

    @MainActor
    func testExportFailureKeepsLocalCapture() async throws {
        let library = CaptureLibrary()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // A conflicting file prevents creation of the export Photos directory.
        try Data().write(to: folder.appendingPathComponent("Photos"))
        library.selectFolder(folder)
        let input = FileManager.default.temporaryDirectory.appendingPathComponent("test-\(UUID()).jpg")
        let data = Data([0x06])
        try data.write(to: input)
        let local = CaptureLibrary.root.appendingPathComponent("Photos").appendingPathComponent(input.lastPathComponent)
        defer {
            library.useLocalFolder()
            try? FileManager.default.removeItem(at: local)
            try? FileManager.default.removeItem(at: folder)
        }
        await library.save(input)
        XCTAssertEqual(try Data(contentsOf: local), data)
        XCTAssertTrue(library.message?.contains("Folder copy failed") == true)
        XCTAssertTrue(library.files.contains(local))
    }
}
