# Accessibility

The sdui-kmp framework targets **WCAG 2.2 Level AA** conformance for every widget shipped
in `widgets-core`, `widgets-forms`, `widgets-media`, and `widgets-nav`. This document is
the entry point for adopters and contributors who need to verify that target on a real
device or in CI.

## What "WCAG 2.2 AA" means for sdui-kmp

| WCAG Success Criterion | What the framework guarantees |
|---|---|
| 1.1.1 Non-text Content | `Image.contentDescription` and `Image.a11y.label` reach the platform's image alt text. The placeholder loader marks decorative-looking placeholders `invisibleToUser()` when no description is supplied. |
| 1.3.1 Info and Relationships | `A11yRole.Header` + `A11y.headingLevel` mark headings; `A11yRole.Checkbox/Radio/Switch` set the matching Compose `Role`. |
| 2.4.3 Focus Order | Source order in the protocol tree drives traversal. Bottom-sheet routes carry a `paneTitle` so screen readers announce on open. |
| 2.4.6 Headings and Labels | TextField projects placeholder onto contentDescription as a fallback when the server didn't supply `a11y.label`. |
| 2.5.5 / 2.5.8 Target Size | Every interactive widget is pinned to a minimum 48 dp x 48 dp tap region (`ButtonRenderer`, `CheckboxRenderer`). |
| 3.3.1 Error Identification | `TextFieldRenderer` emits `error()` and `stateDescription` when validation fails. |
| 3.3.2 Labels or Instructions | Forms are labelled via `A11y.label`; error supporting text reflects the server's validation message. |
| 4.1.2 Name, Role, Value | Every interactive widget has a non-empty role (Button, Checkbox, Image) and an accessible name from `A11y.label` or the visible label. |

## Audit summary (M-P26)

The M-P26 conformance pass changed the following renderer files. Each change is local
and additive — no protocol fields were added (those land via
[Proposal 0001](../proposals/0001-accessibility-protocol-additions.md) after explicit
acceptance).

| File | Change |
|---|---|
| `widgets-core/.../ButtonRenderer.kt` | Pin minimum height & width to 48 dp before `applyA11y`. |
| `widgets-forms/.../CheckboxRenderer.kt` | Wrap the Row in `Modifier.toggleable` with `Role.Checkbox`; lift Row height to 48 dp; remove inner Checkbox `onCheckedChange` (parent toggleable handles it). |
| `widgets-forms/.../TextFieldRenderer.kt` | Project placeholder onto contentDescription when `a11y.label` is null; emit `error()` + `stateDescription` on validation failure. |
| `widgets-media/.../ImageLoader.kt` | Placeholder box gets `Role.Image` + contentDescription when supplied; `invisibleToUser()` otherwise. |
| `widgets-nav/.../BottomSheetNavRenderer.kt` | `paneTitle` semantic for screen-reader announcement on open; `applyA11y` on the inner host. |

The unchanged renderers (`TextRenderer`, `ColumnRenderer`, `LazyListRenderer`,
`AsyncImageRenderer`, `ImageRenderer`, `TabNavRenderer`, `NavHostRenderer`) already
satisfy AA at the time of audit — the existing `applyA11y` plumbing covers them.

## How to test

* **Automated**: the [accessibility snapshot suite](../../tooling-snapshot/src/test/kotlin/dev/sdui/kmp/tooling/snapshot/A11ySnapshotTest.kt)
  runs in `verifyGoldenSnapshots` and checks role / contentDescription / touch-target on
  every shipped widget. Adopters reuse [`A11yAssertions`](../../tooling-testing/src/main/kotlin/dev/sdui/kmp/tooling/testing/A11yAssertions.kt)
  in their own UI tests.
* **Manual on Android**: see [talkback-testing.md](talkback-testing.md).
* **Manual on iOS**: see [voiceover-testing.md](voiceover-testing.md).
* **Manual on Desktop**: see [desktop-screen-reader.md](desktop-screen-reader.md).
* **Manual on Web (Wasm)**: see [wasm-aria.md](wasm-aria.md).

## Reporting an a11y bug

File the bug with the WCAG criterion number, the screen tree JSON that reproduces it,
and the platform + screen-reader version. The framework treats AA regressions as
release-blocking.
