// SPDX-License-Identifier: Apache-2.0
//
// SwiftUI entry point for the sdui-kmp iOS sample.
//
// The actual UI is rendered by the Compose Multiplatform `ComposeUIViewController`
// returned from Kotlin in `:samples:sample-ios`. This file just owns the application
// lifecycle (`@main`) and the single window scene that hosts `ContentView`.

import SwiftUI

@main
struct iosAppApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.keyboard)
        }
    }
}
