# Wasm / Web ARIA — Compose for Web

Compose Multiplatform's Wasm target renders to a `<canvas>` element via Skia/Skiko, NOT
to native HTML elements. This has direct accessibility consequences that adopters need
to understand before shipping a Wasm bundle as the public face of their product.

## What works

Compose for Web 1.7 exposes its semantics tree through a parallel ARIA-mapped DOM
overlay: every Compose `semantics { ... }` block produces a hidden `<div>` with the
matching `role`, `aria-label`, `aria-describedby`, etc. Screen readers (JAWS, NVDA,
VoiceOver) traverse the overlay rather than the canvas. The renderer maps:

| Compose semantics | DOM ARIA |
|---|---|
| `contentDescription` | `aria-label` |
| `Role.Button` | `role="button"` |
| `Role.Checkbox` + `toggleable` | `role="checkbox"` + `aria-checked` |
| `Role.Image` | `role="img"` |
| `heading()` + `headingLevel = N` | `role="heading"` + `aria-level="N"` |
| `liveRegion = Polite` | `aria-live="polite"` |
| `liveRegion = Assertive` | `aria-live="assertive"` |
| `invisibleToUser()` | `aria-hidden="true"` |
| `stateDescription` | `aria-valuetext` |
| `paneTitle` | `aria-label` on a `role="dialog"` element when a modal is shown |

The sdui-kmp framework's existing `applyA11y` helper inherits all of these for free —
the same `Modifier.semantics` block that drives Android TalkBack drives the Wasm ARIA
overlay.

## Known limitations

* **Keyboard navigation through the canvas surface.** Compose for Web installs key
  listeners on the canvas. Tab order between the canvas and other DOM elements on the
  hosting page is fragile; embed sdui-kmp Wasm bundles inside a containing element with
  `tabindex="0"` so the focus enters the canvas predictably.
* **No native form autofill.** Browsers' password managers and address autofill operate
  on real `<input>` elements, not on a canvas overlay. Adopters who need autofill must
  fall back to platform-native renderers (Android, iOS, Desktop) or wrap their canvas
  in a hidden `<form>` and proxy the values into the SDUI `StateStore` manually. This
  is a Compose Multiplatform limitation, not an sdui-kmp one.
* **Touch target sizes** — the framework's 48 dp minimum on Button/Checkbox is in dp,
  not CSS px. At density 1.0 they coincide; at retina densities the rendered size in
  CSS px is double, which is fine. Devices with custom densities (Android Chrome zoom,
  desktop browser zoom) may compress the rendered region; adopters should test at
  100 % / 125 % / 150 % zoom.
* **Reduced motion.** Compose for Web does not currently honour
  `prefers-reduced-motion`. The protocol has no animation surface today (animations are
  an explicit non-goal per [VISION.md](../../VISION.md)), so this only matters once an
  adopter adds custom animated widgets.
* **Screen reader bug parade.** NVDA + Firefox + Compose Wasm has known issues
  announcing `aria-live` updates (https://github.com/JetBrains/compose-multiplatform/issues/4032).
  We recommend Chromium-based browsers + JAWS for production verification on Windows.

## Verification

Use the browser's accessibility tree inspector (Chrome DevTools → Lighthouse →
Accessibility audit, or Firefox → Inspector → Accessibility tab) to confirm that every
sdui-kmp screen produces the expected ARIA tree. The Lighthouse audit's "Accessibility"
score is a useful smoke test; a score < 95 indicates a regression.

## Production readiness

The framework formally claims WCAG 2.2 AA on Wasm only when:

1. The host page wraps the canvas in a focus-managed container.
2. The browser is a current Chromium or Firefox.
3. The screen reader is a current JAWS, NVDA, or VoiceOver.

Outside that envelope (legacy browsers, non-mainstream screen readers), conformance is
best-effort.
