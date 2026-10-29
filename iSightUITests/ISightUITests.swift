import XCTest

final class ISightUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Camera access") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            return false
        }
    }

    func testHomeEndsAtPrivacyPolicy() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["Open Camera Live View"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Select Storage Location"].exists)
        XCTAssertTrue(app.links["Privacy Policy"].exists || app.buttons["Privacy Policy"].exists)
        XCTAssertFalse(app.staticTexts["A closer look. A clearer view."].exists)
        XCTAssertFalse(app.buttons["Use app storage only"].exists)
        XCTAssertFalse(app.buttons["Captures"].exists)
    }

    func testLiveViewKeepsControlsOnRightInLandscape() throws {
        if #available(iOS 26.0, *), UIDevice.current.userInterfaceIdiom == .pad {
            throw XCTSkip("New iPadOS windowing can override landscape-only scene requests; test physical iPad behavior separately.")
        }
        let app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        app.buttons["Open Camera Live View"].tap()
        let record = app.buttons["Start recording"]
        app.tap() // Let XCTest handle a first-launch camera permission alert.
        XCTAssertTrue(record.waitForExistence(timeout: 10))
        for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let layout = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                let frame = app.frame
                return frame.width > frame.height && record.frame.midX > frame.midX
                    && app.buttons["Take photo"].frame.midY > record.frame.midY
                    && app.buttons["live-menu"].isHittable
                    && app.buttons["live-menu"].frame.maxX < frame.midX
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [layout], timeout: 8), .completed)
        }
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "Landscape Live View with fixed right-side controls"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
