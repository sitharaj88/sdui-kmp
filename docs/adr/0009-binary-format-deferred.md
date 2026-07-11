# ADR 0009 — Protobuf opt-in transport deferred (JsonElement serializer is JSON-only)

**Status:** accepted (Phase 8, M8)

## Context

[ROADMAP.md:82](../../ROADMAP.md#L82) lists "Protobuf serialization as an opt-in transport
for heavy screens" as part of M8. The goal is to let hosts negotiate
`application/vnd.sdui+protobuf` for screens where JSON size or parse latency bites.

Phase 8 attempted to add a `:protocol-protobuf` module exposing a `SduiProtobuf` binary
codec built on `kotlinx-serialization-protobuf`.

## Problem

`kotlinx.serialization.json.JsonElementSerializer.serialize()` begins with:

```kotlin
fun serialize(encoder: Encoder, value: JsonElement) {
    verify(encoder)                          // casts encoder to JsonEncoder
    ...
}
```

It hard-requires a `JsonEncoder`. Outside the `Json` format, every call throws
`IllegalStateException: This serializer can be used only with Json format.`

The protocol uses `JsonElement` / `JsonObject` fields throughout, by deliberate design ([ADR-0001](0001-value-t-stores-jsonelement.md)):

- `Value.Literal.value: JsonElement`
- `Action.UpdateState.value: Value<JsonElement>`
- `Predicate.Eq.value: JsonElement`
- `NativeSurface.config: JsonObject`
- `Screen.initialState: Map<StatePath, JsonElement>`
- `StateDeclaration.initial: JsonElement`
- `Destination.ScreenDest.args: JsonObject`
- `ListSource.Inline.items: List<JsonObject>`
- `LiveEvent.StateUpdate.updates: Map<StatePath, JsonElement>`

Transitively, nearly every non-trivial screen carries at least one JsonElement. Protobuf
encoding fails on all of them.

## Options considered

1. **Hack — tunnel JsonElement through as a JSON string inside the protobuf envelope.** Works via a custom `KSerializer<JsonElement>` that calls `Json.encodeToString` and `encoder.encodeString`. But contextual override requires `@Contextual` annotations on every affected field — dozens of changes across `:protocol`. And it's aesthetically a betrayal — you pay protobuf's complexity and keep JSON's per-JsonElement size overhead.
2. **Migrate off JsonElement.** Replace the untyped payload fields with a typed `SduiValue` sealed hierarchy (Primitive.String / Primitive.Int / Primitive.Double / Primitive.Bool / Array / Object). Every Value.Literal, NativeSurface.config, initialState, Eq.value, etc. transitions to the new type. Full multi-phase refactor. ADR-0001 itself would be revisited.
3. **Defer Protobuf.** Keep JSON-only for now; note the blocker and come back when either (a) kotlinx-serialization relaxes the JsonEncoder requirement, or (b) we migrate to option 2 as a deliberate Phase.

## Decision

Option 3. Defer.

## Rationale

- The benefit (smaller payloads, faster parse) is real but not urgent at the current
  protocol surface. Performance data from Phase 8's benchmarks shows JSON encode/decode of a
  100-node `Screen` takes **~100–200 µs** on a modern laptop — well under a 16 ms frame
  budget even at 5000 rows.
- The cost of option 1 (the hack) is permanent surface-area noise: every `@Contextual`
  annotation is a bookkeeping item forever. The cost of option 2 is a protocol-wide
  refactor that reverses ADR-0001. Neither pays for itself at current load.
- When we hit a screen where JSON is genuinely the bottleneck (very large LazyList.Inline
  payloads shipped in one screen tree, say), option 2 becomes the principled move — and
  that's also the moment the protocol probably needs other shape changes.

## Consequences

- `SduiJson` remains the sole wire format for the foreseeable future. `:transport-http`
  already uses it; no behavior change.
- The ROADMAP milestone item for Protobuf is explicitly unresolved; this ADR is the tracking
  reference.
- If a user requests Protobuf, the answer is: "ship option 2" or "wait for the upstream
  JsonElement fix". Not: "flip the switch".

## Scope note

The `kotlinx-serialization-protobuf` library is itself solid and works for any protocol
without JsonElement fields. If a future module defines its own typed protocol slice (e.g. a
telemetry metrics wire format separate from `sdui-kmp`'s UI tree), that slice can ship
Protobuf immediately without waiting for this blocker.
