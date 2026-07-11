// SPDX-License-Identifier: Apache-2.0
//
// Bridge between SwiftUI and the Compose Multiplatform UI exported from Kotlin.
//
// `SduiSampleShared` is the framework produced by
// `:samples:sample-ios:linkPodDebugFrameworkIosSimulatorArm64` (or the equivalent
// Release/device task), embedded into this app target via the `Run Script` build
// phase that invokes `:samples:sample-ios:embedAndSignAppleFrameworkForXcode`.
//
// The Kotlin factory `SduiSampleViewControllerKt.SduiSampleViewController(baseUrl:)`
// returns a `UIViewController` we wrap with `UIViewControllerRepresentable` so SwiftUI
// can host it inside a `WindowGroup`.

import SwiftUI
import UIKit
import SduiSampleShared

struct ComposeView: UIViewControllerRepresentable {
    /// Override the default `http://localhost:8080` to point at a LAN IP, ngrok URL,
    /// or staging environment. The simulator shares its network namespace with the
    /// host Mac, so `localhost` is the right value when running the sample server
    /// locally.
    let baseUrl: String

    func makeUIViewController(context: Context) -> UIViewController {
        // Kotlin top-level functions land on `<BaseName>Kt` in the generated Obj-C
        // header. The KDoc'd default argument is preserved as an `init(baseUrl:)`
        // companion-style overload — passing it explicitly keeps the Swift call
        // site self-documenting.
        return SduiSampleViewControllerKt.SduiSampleViewController(baseUrl: baseUrl)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op: the Compose host owns its own state internally and does not need
        // a SwiftUI-driven re-render. If you ever pass dynamic config (theme, locale)
        // through `baseUrl` or sibling parameters, recreate the view controller here
        // by setting an `id(...)` on the SwiftUI side rather than mutating in place.
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView(baseUrl: "http://localhost:8080")
            .ignoresSafeArea(.all, edges: .bottom)
    }
}

#Preview {
    ContentView()
}
