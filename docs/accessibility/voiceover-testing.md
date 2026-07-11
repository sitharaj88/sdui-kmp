# VoiceOver testing — iOS

Manual verification for the iOS sample (`iosApp/` consumes `samples/sample-ios`'s
`SduiSampleShared.framework`).

> Local prerequisite: full Xcode + iOS Simulator. The sdui-kmp main repo CI runs iOS
> tests on macOS-14 GitHub runners; local developers without full Xcode install must
> verify via the simulator on a colleague's machine or the CI artifact.

## Setup

1. Open `iosApp/iosApp.xcodeproj` in Xcode.
2. Run the app on an iPhone simulator (any iOS 16+).
3. Settings → Accessibility → VoiceOver → On. Or: triple-press the side button if
   accessibility shortcuts are configured.
4. Drag two fingers up to read the screen continuously; tap with one finger to focus a
   single element.

## Per-screen verification matrix

| Screen | Expected announcement | Pass criteria |
|---|---|---|
| Home | "Welcome back, heading" | The Compose `heading()` modifier maps to UIKit's `accessibilityTraits.header`; VoiceOver reads "heading" suffix. |
| Login form | For each `TextField`: resolved label or placeholder. The Submit button: "Submit, button". | No nameless edit fields. Tapping Submit announces validation errors via `accessibilityValue`. |
| Image-rich screens | Each `Image` with a `contentDescription` is announced; placeholder boxes without descriptions are skipped (`accessibilityElementsHidden`). | VoiceOver swipes do NOT land on decorative images. |
| Bottom sheet | On present: announces the `paneTitle`. On dismiss: returns focus to the trigger element. | The sheet's first focusable child receives focus automatically. |

## Compose-Multiplatform → UIKit mapping

| Compose semantics | UIKit accessibility |
|---|---|
| `contentDescription` | `accessibilityLabel` |
| `stateDescription` | `accessibilityValue` |
| `Role.Button` | `accessibilityTraits.button` |
| `Role.Image` | `accessibilityTraits.image` |
| `heading()` | `accessibilityTraits.header` |
| `liveRegion = Polite` | `UIAccessibility.post(notification: .announcement, ...)` (CMP 1.7 implements this on Polite messages but not Assertive — see Known Gaps below) |
| `invisibleToUser()` | `accessibilityElementsHidden = true` |
| `paneTitle` | `accessibilityViewIsModal = true` + announcement on present |

## Known gaps

* **Assertive live regions** — Compose Multiplatform 1.7.3 maps `LiveRegion.Polite` to
  the iOS announcement notification but does not implement `Assertive` distinctly.
  Until the upstream supports both, `LiveRegion.Assertive` falls back to Polite on iOS.
  Track at https://issuetracker.google.com/issues/256598087 and the corresponding
  Compose Multiplatform issue.
* **Dynamic type scaling** — sdui-kmp's protocol uses semantic text style tokens
  (`TextStyleToken.Body` etc.). Compose Multiplatform 1.7 does not yet honour iOS's
  `UIContentSizeCategory` automatically; the renderer will pick up dynamic type when CMP
  exposes it. Adopters that need it today must wrap `SduiHost` in a fontScale-aware
  `MaterialTheme`.

## Recording results

Use Xcode → Debug → Accessibility → "Capture Accessibility Audit" to dump the inspector
tree per screen. Attach the audit JSON to the adopter's release-readiness checklist.
