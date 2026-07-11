# Desktop screen-reader testing — JAWS / NVDA / VoiceOver (macOS)

Compose Multiplatform Desktop renders to Skia and exposes its semantics tree through the
JVM's accessibility bridge. The OS-specific story:

| OS | Bridge | Screen reader |
|---|---|---|
| Windows | UI Automation via the JDK's `AccessBridge` | NVDA (recommended), JAWS |
| macOS | NSAccessibility via Java's `Cocoa Bundle` accessibility | VoiceOver |
| Linux | AT-SPI via the `java-access-bridge-bin` package | Orca |

## Setup — Windows + NVDA

1. Install NVDA from https://www.nvaccess.org (free).
2. Build and run `samples/sample-desktop`: `./gradlew :samples:sample-desktop:run`.
3. Press `NVDA + Tab` to read the focused widget. Press `NVDA + Down` to read continuously.

## Setup — macOS + VoiceOver

1. System Settings → Accessibility → VoiceOver → On (`Cmd + F5`).
2. Run the desktop sample as above.
3. Use the VO modifier (`Ctrl + Option`) plus arrow keys to navigate.

## Verification matrix

| Widget | Expected | Notes |
|---|---|---|
| Button | Reads as "<label>, button". | Compose Desktop maps `Role.Button` to NSAccessibilityButtonRole / UIA Button. |
| Checkbox | Reads as "<label>, checkbox, checked/unchecked". | The toggleable Row wrapper supplies the label. |
| TextField | Reads as "<label>, edit text" (focus enters), "<value>" (typing). | Validation errors fire announcements through `liveRegion = Polite` if the server sets it. |
| Image | Reads `contentDescription`. Decorative placeholders are skipped. | |
| Tab navigation | Each tab reads its label and selected state. | NavigationBarItem natively exposes `accessibilitySelected`. |

## Known gaps

* **Compose Desktop's accessibility bridge is JVM-version-sensitive.** JDK 17 works
  reliably; older JDKs miss several semantic mappings. The convention plugins pin
  `jvmToolchain(17)` precisely for this reason.
* **Orca on Linux** — Compose Multiplatform 1.7's AT-SPI implementation is incomplete;
  expect partial coverage of role announcements. Tracking issue:
  https://github.com/JetBrains/compose-multiplatform/issues/2940. Until upstream lands
  the work, sdui-kmp does not formally claim WCAG conformance on Linux desktop.
* **High-contrast mode** — sdui-kmp's color tokens resolve from `MaterialTheme`, which
  inherits the system contrast preference on macOS Sonoma+ but not on Windows. Adopters
  who need high-contrast on Windows must wrap their host in a custom
  `MaterialTheme(colors = ...)` keyed off the OS preference.

## Recording results

Use NVDA's "Speech Viewer" (Tools → Speech Viewer) on Windows to capture the per-focus
announcement. On macOS use Accessibility Inspector (Xcode → Open Developer Tool →
Accessibility Inspector) which works against any UI element including JVM windows.
