# ADR-0019: Studio visual editor architecture

Status: Accepted (M-S5)

## Context

Until this ADR the Studio CMS exposed a single editor surface: a monospace
`OutlinedTextField` whose contents were the raw `Screen` JSON. That works for engineers, but
the framework's audience over its 5–10 year lifespan is content / product editors who do not
read JSON natively. We need a drag-drop visual editor alongside the JSON editor:

* Both must round-trip into identical JSON (no lossy summarisation).
* Both must share the same backing draft state — switching modes must never silently lose
  edits.
* The editor must be a downstream consumer of `:protocol`. It cannot reach into
  `:runtime`'s production renderer hot path (which fires actions and assumes a real state
  store), and it cannot violate the framework's non-negotiables — in particular, the
  inspector must never expose hex colours or pixel sizes.

## Decision

### Edit-mode renderer separate from production `SduiHost`

The visual canvas (`EditorCanvas`) walks the `UiNode` tree by hand instead of delegating to
`SduiHost`. Three reasons:

1. **Inert rendering.** The editor must not fire `Action.Submit`, mutate the state store, or
   route navigation. A dedicated renderer is simpler than threading mock dispatchers
   through `SduiHost`.
2. **Selection chrome.** Each node needs its outline, click-to-select hit area, and (for
   containers) drop-zone bookkeeping registered with the workspace. Wedging this into the
   widget renderers in `:widgets-*` would couple production rendering to editor concerns.
3. **Drop-target hit-testing.** Drag resolution needs each container's window-space
   rectangle, which the production renderer has no reason to track.

The two renderers share `:protocol` — the same `UiNode` is the source of truth on both
sides. The "live preview" pane in JSON mode still uses `SduiHost`, exactly as before.

### Undo / redo via opaque `Op` log on a `JsonElement`-mutating mutator

`TreeMutator` operates against the typed `UiNode` root, but every transformation routes
through `kotlinx.serialization.json` by encoding the affected sub-tree to a `JsonObject`,
mutating, and decoding back. That keeps the mutator agnostic to the concrete `UiNode`
sub-class set: adding a new container or leaf to `:protocol` does not require changes here,
matching the framework's additive-evolution rule.

The undo/redo stack stores `(Op, beforeRoot)` pairs. On undo we swap `beforeRoot` back into
state and push the inverse onto the redo stack. We capture pre-state instead of computing
algorithmic inverses because:

* `Op.SetField` with a missing key vs a present key is genuinely different on the wire
  (the protocol uses `encodeDefaults = false`, so the absence of a default-valued field
  is observable). Computing an exact inverse for `SetField` on an arbitrary serialised
  shape was deemed riskier than snapshotting.
* Snapshot-based undo is `O(treeSize)` per op, which is fine: trees are bounded by what
  fits in a Studio editor session and we cap history at 50 entries by default.

The mutator exposes `tree: StateFlow<UiNode>` so the canvas observes changes without
introducing a Compose-snapshot dependency in pure-Kotlin domain code. That also makes the
mutator unit-testable as soon as the `wasmJsTest` task is re-enabled — see ADR-0011 for the
broader Wasm-test gating.

### Token-aware property inspector via hand-written switch, not reflection

The inspector switches on the concrete `UiNode` type and renders one row per editable
field, dispatching changes as `Op.SetField` operations. We rejected a reflection-driven
walk over `KSerializer.descriptor` because:

1. **Wasm reflection surface.** `kotlin.reflect` on Kotlin/Wasm is more limited than on
   JVM. A descriptor-driven UI would fork between targets.
2. **Token enforcement.** The framework's third non-negotiable bans hex colours and pixel
   sizes in widget fields. Token-only pickers must be visible at the call site so a future
   reviewer cannot accidentally regress this. A reflection layer would have to special-case
   `ColorToken`, `Spacing`, and `TextStyleToken` anyway, at which point we are already
   writing per-field UI.

For colour fields the inspector renders a hard-coded enumeration of `ColorToken` values
(plus a `(none)` option for nullable slots). For spacing and typography fields it renders
the enum's `entries`. The inspector never exposes a hex picker, a `Color(0xff...)` field, or
a numeric `dp` input. Operators wanting "exotic" values must go through the JSON tab and
the schema linter will catch protocol violations the same way it does today.

### Shared draft state across Visual / JSON modes

Both editor modes write to the same `text: String` state in `EditorPanes`. In Visual mode
we seed the `TreeMutator` from the last successfully-decoded `Screen.root`, and on every
mutation we re-encode `Screen` back to JSON via `SduiJson.encodeToString`. In JSON mode the
existing debounced decode runs unchanged. Switching modes is byte-for-byte lossless.

The Save / Publish / Discard / Revert buttons remain at the top level and operate on
`text` exactly as before — there is no Visual-only save path.

### Drag-drop without HTML5 APIs

Compose-Multiplatform on Wasm renders into a Skia canvas; the DOM-level drag-drop API is
not available to us. Both palette entries and canvas nodes use Compose's
`detectDragGestures` plus `onGloballyPositioned` to track each candidate drop target's
window-space rectangle. The drag resolver picks the deepest container that contains the
pointer and is not a descendant of the dragged source (cycle prevention). Cycle detection
also exists in `TreeMutator.applyMove` itself — defence in depth, since corrupting the tree
into a cycle would crash the editor canvas on next render.

## Consequences

* New protocol nodes need a corresponding `WidgetDescriptor` entry in `DefaultWidgetPalette`
  and (for non-leaf-decorative fields) inspector rows. Missing palette entries fail closed
  — the operator simply cannot reach the widget visually — so this is a soft requirement.
* Undo history is in-memory only. Closing the browser tab loses the stack. Persistence
  comes for free via the existing Save → server flow; we deliberately do not wire undo to
  the server-side draft store.
* The editor is currently container-aware only for `Column`. Any future `Container`
  sub-type works automatically because the mutator detects them via the `Container`
  interface; the only manual step is updating the canvas's drop-target rendering, which
  already iterates `Container.children` generically.

## Alternatives considered

* **Reuse `SduiHost` for the canvas.** Rejected — see the "edit-mode renderer" section.
* **Reflection-driven inspector via `KSerializer.descriptor`.** Rejected — see the
  "token-aware property inspector" section.
* **HTML5 drag-drop via `js("")` interop.** Rejected — the rest of the Studio is pure
  Compose, and dropping into raw DOM here would force the rest of the editor to track
  HTML element positions in parallel with Compose layout.
* **Persisting undo to the server.** Deferred. The existing draft / publish loop already
  gives editors a coarse-grained "saved snapshot" rollback; tying undo to per-op
  persistence would balloon the server's storage footprint.
