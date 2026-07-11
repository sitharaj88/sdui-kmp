# ADR 0007 — Fixture JSON is hand-authored, not generated

**Status:** accepted (Phase 2, M2.6)

## Context

`:protocol-fixtures` contains a corpus of typed protocol instances plus their expected wire
JSON:

```kotlin
public data class NodeFixture(
    public val name: String,
    public val node: UiNode,
    public val json: String,
)
```

The contract tests assert `SduiJson.encodeToString(node) == json` for every fixture.

## The temptation

Regenerate the `json` field by running the encoder once at test authorship time. Saves
effort, guarantees fixtures always match.

## Decision

Author `json` by hand. Keep it that way.

## Rationale

- The whole point of the contract test is to catch **silent encoder drift**. If `SduiJson`
  starts emitting defaults that were previously omitted, or changes key ordering, or drops
  a null handling subtlety, the fixture test fails loudly and we review the change. A
  self-regenerating fixture corpus would hide that drift.
- Hand-authored fixtures serve double duty as worked examples of the wire format for
  human readers. A `null` field difference or a subtle key reorder is obvious in a hand-
  written example in a way it isn't in a generated blob.
- Adding a new fixture is cheap: write the typed instance, run the test, copy the expected
  string from the assertion failure into `json`. One round-trip, forever after the JSON is a
  contract.

## Consequences

- Adding a new `NodeFixture` requires writing the JSON by hand. Measured cost in practice:
  ~1 minute per fixture.
- Any change to encoder defaults (e.g. `encodeDefaults = true`) immediately fails the
  contract test across all fixtures — which is exactly the right signal.
