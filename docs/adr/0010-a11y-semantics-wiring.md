# ADR 0010 — A11y semantics wired via a single `Modifier.applyA11y` helper

**Status:** accepted (Phase 8, M8)

## Context

Every widget in the protocol accepts an optional `a11y: A11y?` field. Phases 1–7 defined
the field and threaded it through the data classes, but renderers didn't actually project it
onto Compose's `Modifier.semantics { ... }` — so the server-authored a11y metadata
(`label`, `role`, `liveRegion`, `headingLevel`, `isHidden`) didn't reach screen readers.

## Decision

Add a single helper in `:runtime`:

```kotlin
public fun Modifier.applyA11y(a11y: A11y?, store: StateStore): Modifier
```

Every widget renderer that accepts `a11y` calls `modifier.applyA11y(node.a11y, store)`
before passing the modifier to the Compose primitive. The helper maps protocol fields to
Compose semantics:

| Protocol | Compose |
|---|---|
| `A11y.label` | `contentDescription = <resolved string>` |
| `A11y.hint` | `stateDescription = <resolved string>` |
| `A11y.role` | `role = <mapped Role>` (see below) |
| `A11y.headingLevel != null` | `heading()` |
| `A11y.isHidden` | `invisibleToUser()` |
| `A11y.liveRegion == Polite` | `liveRegion = LiveRegionMode.Polite` |
| `A11y.liveRegion == Assertive` | `liveRegion = LiveRegionMode.Assertive` |

## Rationale

- **One plumbing site**, not 9 (one per renderer). Adding a new semantic field touches one
  file.
- **Protocol roles that Compose doesn't model (Link, Header, List, ListItem, Slider,
  TextField) fall through to the widget renderer's native semantics.** `Text` with
  `A11yRole.Header` gets `heading()` via `headingLevel`; `Link` would live in a future link
  widget whose renderer emits the right behavior directly. No protocol-level loss; explicit
  mapping for the intersection.
- **Server-provided values override; absent values leave Compose defaults alone.** A
  `Button` without an explicit `A11y.label` still has the button text itself as the content
  description (Compose handles that). `applyA11y` only *adds* to semantics, never clears.

## Consequences

- `node.a11y` fields authored by the server now reach assistive tech. The tracking-screen
  `NativeSurface` fallback, the login form's `A11yRole.TextField` annotations, etc., all
  propagate.
- The helper lives in `:runtime` because it needs `LocalStateStore` to resolve
  `Value<String>` labels. Widget modules transitively see it via their `:runtime` dep —
  no new module boundary.
- Semantic role coverage is narrower than the protocol's `A11yRole` enum. If Compose
  extends `Role` to include e.g. `Header`, update the helper's `when` branch and existing
  headers upgrade for free. This `when` is sealed over `A11yRole`, so adding a new enum
  case breaks the compile until handled — same safety invariant as the patch engine's
  exhaustiveness sentinel in [ADR-0005](0005-native-surface-fallback.md).
