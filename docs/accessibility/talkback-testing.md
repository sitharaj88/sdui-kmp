# TalkBack testing — Android

Step-by-step manual verification for the sample-android app. Repeat for every adopter
build before tagging a release.

## Setup

1. Install `samples/sample-android` on a physical device running Android 12 or newer.
   TalkBack's behaviour on emulators differs from real hardware (haptics, gesture
   detection); always test on physical.
2. Settings → Accessibility → TalkBack → On.
3. Enable Developer Settings → "Show layout bounds" so you can correlate visible bounds
   with what TalkBack focuses on.

## Per-screen verification matrix

| Screen | Expected announcement | Pass criteria |
|---|---|---|
| Home (`screens/home`) | "Welcome back, heading, level 1" | Heading is announced as level 1. Body text is read in source order. |
| Login (`screens/login`) | For each TextField: the resolved a11y.label OR the placeholder. | No "edit box, double tap to edit" without a name. The "Submit" button announces "Submit, button, double tap to activate." |
| Settings tabs (`NavHost(kind=Tab)`) | Each tab announces its label. Switching tabs reads the new pane title. | TalkBack focus moves to the first item of the new tab on switch. |
| Bottom-sheet (`NavHost(kind=BottomSheet)`) | On open: announces the `paneTitle` (the resolved `a11y.label` or the route name). | A swipe-down gesture closes the sheet; TalkBack reads "Sheet collapsed." |
| Image gallery (`AsyncImage` per cell) | Each cell announces `contentDescription`. Decorative placeholders do not steal focus. | Swiping right walks images in source order; no "image" without a label. |
| Form validation | When validation fails, announcement reads the error text via the supportingText slot. | The next focus on the field reads `stateDescription` (the error message). |

## Common failures and fixes

- **"Edit box, double tap to edit"** — the TextField has no accessible name. Server
  must set `a11y.label` or the renderer's placeholder fallback must be in place. See
  `TextFieldRenderer.kt`.
- **"Button"** with no name — almost always means a custom widget skipped `applyA11y`.
  Run the [`A11ySnapshotTest`](../../tooling-snapshot/src/test/kotlin/dev/sdui/kmp/tooling/snapshot/A11ySnapshotTest.kt)
  suite locally to find the renderer.
- **Touch target too small** — TalkBack reads the node, but a sighted user with motor
  impairment can't tap reliably. Confirm the renderer enforces `Modifier.heightIn(48.dp)`
  and `widthIn(48.dp)`.
- **Wrong reading order** — until [Proposal 0001](../proposals/0001-accessibility-protocol-additions.md)
  lands the `traversalIndex` field, the only fix is to reorder the protocol tree on the
  server.

## Recording results

Tooling: Android Studio's "Layout Inspector" plus `adb shell uiautomator dump` produce
the semantic tree per screen. Store recordings under `docs/accessibility/recordings/`
in the adopter's repo (sdui-kmp itself does not vendor them).
