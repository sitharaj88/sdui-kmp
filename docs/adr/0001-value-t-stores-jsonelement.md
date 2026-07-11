# ADR 0001 — `Value<T>` stores `JsonElement` on the wire

**Status:** accepted (Phase 1, M1.3)

## Context

[ARCHITECTURE.md](../../ARCHITECTURE.md) declares `Value<T>` as a generic sealed interface:

```kotlin
data class Literal<T>(val value: T) : Value<T>
data class Bind<T>(val path: StatePath) : Value<T>
```

We need round-trip serialization for `Text.content: Value<String>`, `Action.UpdateState.value: Value<JsonElement>`, etc.

## Problem

kotlinx-serialization 1.7.3's auto-generated sealed serializer does not thread the
`KSerializer<T>` argument down to subclass serializers. Running
`Json.encodeToString(Text.serializer(), Text(content = Literal("hi")))` throws
`Serializer for subclass 'String' is not found in the polymorphic scope of 'Any'` — the
Literal descendant defaults T's serializer to `PolymorphicSerializer(Any::class)`.

Reproduced with both `Value<T>` and `Value<out T>` variance. Not project-specific:
[kotlinx.serialization issue threads](https://github.com/Kotlin/kotlinx.serialization/issues)
track this class of bug.

## Options considered

1. **Custom `KSerializer<Value<T>>`** — write a hand-rolled polymorphic serializer that threads T. ~80 LOC, maintenance burden when we add variants. Correct but expensive.
2. **Enumerate concrete subtypes per T** — `StringLiteral`, `IntLiteral`, `JsonLiteral` with distinct `@SerialName`s. Duplicates the discriminator, deviates from architecture wording.
3. **Store the literal as `JsonElement` always** — keep `Value<T>` as a phantom type for Kotlin API discipline; at the wire level every literal is a JSON primitive/array/object. Construction via `Value.ofString`, `Value.ofInt`, `Value.ofJson`.

## Decision

Option 3.

- `Value.Literal<T>` has `val value: JsonElement` (not `T`).
- Typed constructors preserve the Kotlin-side T so `Text.content: Value<String>` still typechecks.
- Wire form is unchanged from the architecture's intent: `{"type":"literal","value":"hi"}` — the JsonElement is a JsonPrimitive most of the time.
- Renderers resolve to primitive content via `resolve(store): String` in `:runtime`.

## Consequences

- Minor API drift from architecture.md: callers construct literals via helpers, not the data class constructor directly.
- The `T` in `Value<T>` is purely a compile-time phantom. This is fine — the DSL and renderer care about the Kotlin type; the wire doesn't.
- New value variants (e.g. `Value.Template` added in M3) must also use non-generic payload fields. `Template(val pattern: String, val bindings: Map<String, StatePath>)` works — no `T` appears inside.

## Scope note

If kotlinx-serialization later fixes generic sealed dispatch (e.g. 2.x), we can revisit. The
`Value.ofString(...)` etc. constructors are the API boundary; switching the internal storage
back to `T` would be source-compatible for callers.
