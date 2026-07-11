# Snapshot testing

Widget renderers in `:widgets-core`, `:widgets-forms`, `:widgets-media`, and the runtime's
native-surface placeholder are guarded by golden-image (PNG) snapshots. The suite lives in
`:tooling-snapshot` and runs JVM-only (Compose Desktop). It catches:

- Unintended Material3 token drift (a designer tweaks `primary` and the diff lights up)
- Fat-fingered `Modifier.padding`, alignment, or spacing changes
- Accidental swaps between Material3 components (`Button` vs. `OutlinedButton`)

Android-side snapshots (Paparazzi or instrumented Compose tests) are intentionally deferred.

## Running

```bash
# Verify mode — fails the build on any pixel diff. Hooked into `:tooling-snapshot:check`.
./gradlew :tooling-snapshot:verifyGoldenSnapshots

# Record mode — rewrites every baseline PNG under
# tooling-snapshot/src/test/resources/snapshots/. Commit the result.
./gradlew :tooling-snapshot:recordGoldenSnapshots
```

`./gradlew check` runs the verify task transitively. CI never records.

## When to add a test

Whenever you add a new widget renderer, add at least one snapshot per visually distinct state:

- A new style enum case → one snapshot per case (`button_primary`, `button_secondary`, …)
- A bound state value the renderer reads → snapshot the relevant value (`checkbox_checked`,
  `checkbox_unchecked`)
- A placeholder vs. loaded vs. error state → one snapshot each

Add the test to `WidgetSnapshotTest.kt`, run `recordGoldenSnapshots`, eyeball the new PNG, and
commit it alongside the renderer change.

## How it works

The harness uses Compose Multiplatform's `ImageComposeScene` to render a `@Composable` into a
fixed 400 × 600 dp off-screen canvas at density 1 — no Robolectric, no Roborazzi plugin, no
window. The Skia surface is encoded straight to PNG and compared byte-for-byte against the
committed baseline.

We deliberately did **not** adopt the `roborazzi-compose-desktop` artifact at the time this
suite landed: its plugin had not yet shipped a release matching our Compose 1.7.3 / Kotlin
2.1.0 stack. The hand-rolled harness is small enough (~30 LOC in `SnapshotHarness.kt`) that
swapping it for Roborazzi later is a one-class change — the test surface
(`snapshot(name) { ... }`) is identical to what Roborazzi exposes.

## Determinism

The Gradle test tasks pin `user.language=en`, `user.country=US`, `user.timezone=UTC`, and
`file.encoding=UTF-8` so glyph metrics and text shaping cannot drift between record and
verify runs. If you see a flaky snapshot diff, check first whether your machine ships a
different Skia/skiko build than CI; cross-OS rendering parity is **not** guaranteed and
baselines should be regenerated on the same OS family that CI uses.

## What is not covered

- **Android renderers** — Paparazzi-based snapshots are a separate phase.
- **Animations** — we render a single frame; animation keyframes are out of scope (and the
  protocol doesn't carry them anyway, per VISION.md non-goals).
- **Dark mode / RTL** — only the default light, LTR theme is exercised today. Add explicit
  `dark_*` and `rtl_*` test variants when those pathways start carrying logic.
- **Pixel-tolerant diffing** — the comparator is byte-exact. If anti-aliasing variance
  becomes a problem cross-platform, swap in a perceptual diff (e.g. SSIM) inside
  `SnapshotHarness.kt`.
