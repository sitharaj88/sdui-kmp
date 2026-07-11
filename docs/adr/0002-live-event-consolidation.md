# ADR 0002 — `LiveEvent` is two shapes, not five

**Status:** accepted (Phase 5, M5)

## Context

[ARCHITECTURE.md](../../ARCHITECTURE.md) describes the live transport as pushing
"`StateUpdate`, `TreePatch`, `Append`, `Remove`, `Replace`" events over the WebSocket. The
last three mirror the `PatchOp` sealed subtypes that already exist in `TreePatch`.

## Decision

Consolidate live-push events into two shapes:

```kotlin
sealed interface LiveEvent {
    data class StateUpdate(val updates: Map<StatePath, JsonElement>) : LiveEvent
    data class TreePatchEvent(val patch: TreePatch) : LiveEvent
}
```

`Append`, `Remove`, `Replace` are `PatchOp` variants nested inside a `TreePatchEvent.patch.ops`.
A server wanting to emit a single append sends
`LiveEvent.TreePatchEvent(TreePatch(listOf(PatchOp.Append(...))))`.

## Rationale

- **One source of tree-diff vocabulary.** `PatchOp` defines it in Phase 5; `LiveEvent` reuses it instead of paralleling it. If we add `PatchOp.Move` later, live transports pick it up for free.
- **Fewer discriminators to lint.** `LiveEvent` has `state_update` and `tree_patch`; the per-op discriminators (`replace`, `append`, `remove`) are already linted under `PatchOp`.
- **Same expressivity.** Server that wants to send a single `Remove` sends `TreePatchEvent(TreePatch(listOf(Remove(...))))`. Three extra JSON keys vs a flat `Remove` event — trivial.

## Consequences

- Slight deviation from architecture wording. The intent (tree updates + state updates) is preserved.
- Frameworks/tools that pattern-match on `LiveEvent` subtypes have two cases, not five. Exhaustive `when` stays small.
- Batching: one `TreePatchEvent` can carry many ops applied atomically — the patch engine guarantees order preservation. Free feature, not possible with flat events.
