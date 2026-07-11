# ADR 0006 — In-house `StackNavigator`, not Voyager/Decompose

**Status:** accepted (Phase 2, M2.4)

## Context

[TASK_BREAKDOWN.md:258-263](../../TASK_BREAKDOWN.md#L258-L263) (M2.4) prescribes Jetpack
Navigation Compose on Android plus "Voyager- or Decompose-based implementation in
commonMain" for other platforms.

## Problem

Both Voyager and Decompose are opinionated navigation libraries with their own tree models,
lifecycle abstractions, and state restoration stories. Adopting either pulls a material
design dependency into `:runtime` and a conceptual dependency into every host app. Harder
to reverse than to adopt.

## Decision

Implement a minimal `StackNavigator` in `:runtime/commonMain` — a `PersistentList<String>`
of routes with `push/pop/replace/popToRoot` and a `current: State<String?>` observable. The
M1-scope screens (route → fetch → render) only need stack push/pop.

Android gets a `@Composable InstallAndroidBackHandler(navigator)` helper in
`androidMain` that binds the system back gesture to `navigator.pop()`.

## Rationale

- `Navigator` is the interface seam. A Voyager-backed implementation can land behind the
  same SAM whenever we need advanced features (shared-element transitions, screen-model
  state restoration, nested navigation graphs).
- The sample apps demonstrate navigation end-to-end today (`Home → Feed → Back` works on
  Android, Desktop, Wasm) with no third-party nav library.
- Keeping `:runtime` light means host apps that bring their own navigator (react-navigation
  analogue, native `UINavigationController`, etc.) don't pay for an unused dependency.

## Consequences

- Advanced features (modal-with-custom-transition, bottom-sheet stacks, deep link parsing)
  are future work. The `Navigator` interface already has `switchTab` / `popToRoot`
  signatures so the Destination vocabulary matches architecture.
- `Destination.Modal` currently pushes like a regular screen; proper modal chrome needs
  `NavHost` rendering (M4 protocol, renderer deferred).
