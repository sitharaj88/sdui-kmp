# ADR 0004 — Optimistic-update rollback captures prior values, including unset

**Status:** accepted (Phase 5, M5)

## Context

`Action.Submit` supports `ActionPolicy.optimistic.stateUpdates` — a map of state paths the
client applies immediately while the request is in flight. On failure with
`rollbackOnError = true`, those paths must revert to their pre-submit state.

## Problem

Naïve rollback (restoring only paths that existed before) leaves freshly-created keys stuck
at their optimistic value if the submit fails. Example: a like button writes
`liked = true` optimistically. Submit fails. Before the click, `liked` was *unset* (fell
through to the server-declared initial `false`). Restoring "only if it was there before" leaves
the local scope with `liked = true`, overriding the parent's `false` forever.

## Decision

Before applying optimistic writes, snapshot the *current* value for each affected path —
including when that value is `null` (the key is not set locally). On rollback, if the prior
value was null, **remove** the key from the local scope. Otherwise restore the original.

Required adding `StateStore.remove(path)` so `PersistentMap`-backed local scopes can shed
keys without leaving a null placeholder.

## Consequences

- Correctness holds for the full matrix: `prior-present × optimistic × success|failure` and
  `prior-absent × optimistic × success|failure`. All four permutations tested in
  `OptimisticAndRetryTest`.
- Scoped `StateStore` reads fall through to parents; removing a key from a child scope
  re-exposes the parent's value. A rollback correctly restores the pre-optimistic view even
  when the "prior value" lived in a parent scope.
- `StateStore.remove()` is a new public method on a Tier 2 type. Fine — the semantics are
  obvious and non-breaking.
