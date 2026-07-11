// SPDX-License-Identifier: Apache-2.0
//
// XCUITest happy-path smoke test for the sdui-kmp iOS sample.
//
// What it asserts:
//   1. The app launches without crashing.
//   2. Compose Multiplatform finishes its first composition and the toolbar's
//      "Sign in (sample)" button reaches the accessibility tree.
//   3. The button is tappable (a no-network test environment will surface a
//      "Sign-in failed" status, but that proves the dispatcher round-tripped
//      the click — the button doesn't have to succeed network-wise).
//
// What it does NOT assert:
//   - Screen contents (those depend on `:samples:sample-server` running locally,
//     which is a manual prerequisite documented in the sample README).
//   - Sign-in success (network-dependent; flaky in a UI test).
//
// To run:
//   xcodebuild test -project iosApp/iosApp.xcodeproj \
//     -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 15'

import XCTest

final class iosAppUITests: XCTestCase {
    override func setUpWithError() throws {
        // Stop after the first failure so the simulator log shows the precise step
        // that broke. UI tests are slow; speeding through follow-on assertions on a
        // broken state isn't useful.
        continueAfterFailure = false
    }

    /// Launches the app and verifies the Compose-rendered toolbar is interactive.
    /// Compose Multiplatform exposes button labels through UIKit's accessibility
    /// bridge, so XCUITest finds them via `.buttons[label]` just like a native
    /// SwiftUI button.
    @MainActor
    func testSignInButtonAppearsAndIsTappable() throws {
        let app = XCUIApplication()
        app.launch()

        // The sample's home toolbar always shows "Sign in (sample)" regardless of
        // whether the server is reachable. Compose does its first composition off
        // the main thread; give it a generous-but-bounded window before failing.
        let signIn = app.buttons["Sign in (sample)"]
        XCTAssertTrue(
            signIn.waitForExistence(timeout: 10),
            "Compose 'Sign in (sample)' button did not appear within 10s — " +
            "likely the SduiSampleShared framework failed to embed, or the Compose " +
            "host crashed during first composition. Check the simulator console."
        )
        XCTAssertTrue(signIn.isHittable, "Sign in button is rendered but not hittable")
        signIn.tap()
        // No network assertion: the test passes as long as tapping doesn't crash.
    }
}
