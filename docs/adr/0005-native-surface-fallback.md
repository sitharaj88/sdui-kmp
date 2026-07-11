# ADR 0005 — `NativeSurface` falls back via `UiNode.fallback` on unknown kinds

**Status:** accepted (Phase 6, M6)

## Context

`NativeSurface` is the protocol's typed escape hatch: its `kind` string (`sdui.map`,
`sdui.player`, ...) selects a platform factory at render time. Clients without a registered
factory for some `kind` still need to render *something* safe.

## Options considered

1. **Throw at render time.** Simplest; violates invariant #3 (client never crashes on unknown node).
2. **Render an "unsupported" string.** Protocol-author-unfriendly: the server can't
   control what appears when the client lacks support. Breaks server-author intent.
3. **Reuse `UiNode.fallback`.** Every UiNode already carries an optional fallback tree for
   version-skew scenarios. `NativeSurface` is a UiNode, so it has one too. When no factory
   resolves, the generic `NativeSurfaceNodeRenderer` renders `fallback` (recursively) or
   nothing.

## Decision

Option 3. `NativeSurfaceNodeRenderer` looks up the factory from
`LocalNativeSurfaceRegistry`; if null, fires `telemetry.onUnknownNode("native:<kind>")`
and recurses into `node.fallback`. Identical to how unknown node-type discriminators resolve.

## Rationale

- **One fallback mechanism for the whole protocol.** Discriminator-level fallback
  (decode → `UnknownUiNode` with `fallback`) and sub-widget kind fallback reuse the same
  field. Servers author one fallback story.
- **Invariant #3 holds end-to-end.** Unknown discriminator, unknown version, unknown kind —
  same behavior: render fallback or nothing, never throw.
- **Server-author control.** If a server ships `sdui.map` but suspects older clients lack
  the factory, it sets `fallback = Text("Map unavailable on this platform")`. The tracking
  sample exercises this today (no map factory is registered anywhere in the samples; the
  tracking screen shows its fallback text).

## Consequences

- `NativeSurface.fallback` must be thoughtful. Empty fallback is allowed but leaves a blank
  in the UI — a CI lint could flag `NativeSurface` nodes without fallbacks in the future.
- Telemetry name is `"native:<kind>"` so unknown-kind events can be filtered separately from
  unknown-discriminator events.
