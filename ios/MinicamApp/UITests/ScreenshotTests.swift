import XCTest

/// Logs in against a running minicam and captures the README screenshots
/// (docs/VALIDATION.md "README screenshots"). Skipped unless the server and
/// credentials are passed as TEST_RUNNER_MINICAM_* environment variables.
@MainActor
final class ScreenshotTests: XCTestCase {
    func testCaptureScreens() throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["MINICAM_SERVER"], let email = env["MINICAM_EMAIL"],
              let password = env["MINICAM_PASSWORD"]
        else { throw XCTSkip("MINICAM_SERVER / MINICAM_EMAIL / MINICAM_PASSWORD not set") }
        let camera = env["MINICAM_CAMERA"] ?? "entrada"

        let app = XCUIApplication()
        app.launch()

        let serverField = app.textFields["server"]
        XCTAssertTrue(serverField.waitForExistence(timeout: 10))
        attach(app, name: "ios-login")

        serverField.tap()
        let current = serverField.value as? String ?? ""
        serverField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + server)
        app.textFields["email"].tap()
        app.textFields["email"].typeText(email)
        app.secureTextFields["password"].tap()
        app.secureTextFields["password"].typeText(password)
        app.buttons["signIn"].tap()
        dismissSavePasswordPrompt()

        // The row's label starts with the camera name.
        let cameraRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", camera)).firstMatch
        XCTAssertTrue(cameraRow.waitForExistence(timeout: 15))
        attach(app, name: "ios-cameras")

        cameraRow.tap()
        XCTAssertTrue(app.buttons["PTZ up"].waitForExistence(timeout: 10))
        attach(app, name: "ios-camera")

        // Spec 004: fullscreen with the glass PTZ panel opened and closed.
        app.buttons["Enter fullscreen"].tap()
        XCTAssertTrue(app.buttons["Show PTZ controls"].waitForExistence(timeout: 10))
        attach(app, name: "ios-fullscreen")
        app.buttons["Show PTZ controls"].tap()
        XCTAssertTrue(app.buttons["Hide PTZ controls"].waitForExistence(timeout: 5))
        attach(app, name: "ios-fullscreen-ptz")
        app.buttons["Hide PTZ controls"].tap()
        XCTAssertTrue(app.buttons["Show PTZ controls"].waitForExistence(timeout: 5))
        app.buttons["Exit fullscreen"].tap()
        XCTAssertTrue(app.buttons["PTZ up"].waitForExistence(timeout: 10))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(cameraRow.waitForExistence(timeout: 10))

        // Spec 006: Recordings, for every user.
        app.buttons["moreOptions"].tap()
        app.buttons["Recordings"].tap()
        XCTAssertTrue(app.navigationBars["Recordings"].waitForExistence(timeout: 10))
        attach(app, name: "ios-recordings")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(cameraRow.waitForExistence(timeout: 10))

        // Spec 005: admin sections, only when the test account is an admin.
        for (item, name, marker) in [
            ("Camera settings", "ios-camera-settings", "Add camera"),
            ("AI detection", "ios-ai-detection", "Backend"),
            ("Admin", "ios-admin", "Create"),
        ] {
            app.buttons["moreOptions"].tap()
            let menuItem = app.buttons[item]
            guard menuItem.waitForExistence(timeout: 3) else { return }
            menuItem.tap()
            XCTAssertTrue(app.staticTexts[marker].firstMatch.waitForExistence(timeout: 15)
                || app.buttons[marker].firstMatch.waitForExistence(timeout: 1))
            attach(app, name: name)
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(cameraRow.waitForExistence(timeout: 10))
        }
    }

    /// iOS offers to save the password after login; it covers the screen.
    private func dismissSavePasswordPrompt() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let notNow = NSPredicate(format: "label IN %@", ["Not Now", "Ahora no"])
        for source in [XCUIApplication(), springboard] {
            let button = source.buttons.matching(notNow).firstMatch
            if button.waitForExistence(timeout: 3) {
                button.tap()
                return
            }
        }
    }

    private func attach(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
