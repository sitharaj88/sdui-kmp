# ADR-0014: Property-based protocol decoder tests

* Status: Accepted
* Date: 2026-04-25
* Deciders: framework engineering
* Supersedes / superseded by: —

## Context

`:protocol` is the crown jewel of the framework: every breaking change to the wire format
is a five-year-tail problem. The existing tests in `protocol/src/commonTest/` are
example-based — `ScreenRoundTripTest`, `NodesRoundTripTest`, etc. construct one or two
canonical instances per type and assert that `encode → decode` is the identity.

Example-based tests cover what the engineer thought of when writing them. They miss:

* Empty / boundary collections (size 0, size 1, deeply nested).
* Unicode / control-character payloads in `Value.Literal<String>`.
* `JsonElement` shapes other than `JsonPrimitive` in `Value.Literal`.
* Combinations of the three `Value` shapes inside the same node.
* The third VISION non-negotiable — "client never crashes on unknown discriminator" —
  is exercised by exactly two hand-written cases in `NodesRoundTripTest`.

Property-based testing closes those gaps cheaply.

## Decision

Add **kotest-property** (5.9.x) to `:protocol`'s `commonTest` configuration. We
deliberately do NOT add kotest-runner / kotest-junit; the repo standardises on plain
`kotlin.test`, and changing the runner is a far bigger commitment than picking up a
generator library. Property tests live in
`protocol/src/commonTest/kotlin/dev/sdui/kmp/protocol/property/` and look like:

```kotlin
class UiNodeRoundTripPropertyTest {
    @Test
    fun text_node_roundtrips() = runTest {
        checkAll(arbText) { node ->
            val encoded = json.encodeToString(UiNode.serializer(), node)
            assertEquals(node, json.decodeFromString(UiNode.serializer(), encoded))
        }
    }
}
```

`runTest` from `kotlinx-coroutines-test` bridges the suspend boundary on every KMP
target without needing `runBlocking` (which doesn't exist on Wasm).

### Test files

Three new files:

1. **`UiNodeRoundTripPropertyTest`** — generates random `Text`, `Button`, `Image`,
   `AsyncImage`, and `Column` nodes (the latter recursive to depth `MAX_TREE_DEPTH = 4`),
   asserts structural equality on round-trip.
2. **`ValueRoundTripPropertyTest`** — generates `Value.Literal<String|Int|Boolean>`,
   `Value.Bind`, and `Value.Template`, asserts round-trip.
3. **`UnknownNodeFallbackPropertyTest`** — generates `JsonObject`s with `type`
   discriminators outside the registered set, plus arbitrary extra fields and arbitrary
   `fallback` subtrees, asserts decoding produces `UnknownUiNode` and never throws.

Generators live in `Arbs.kt`, all `internal` because they are test-only and don't
participate in the protocol's `explicitApi()` contract.

### Iteration count

`PropertyTesting.defaultIterationCount = 64` (96 for the unknown-node test). Higher
counts would catch more exotic regressions but at the cost of CI walltime. Engineers
chasing a specific bug can crank it up locally.

### Scope: focused subset, not exhaustive

The kotlinx-serialization sealed hierarchy in `:protocol` is large — `LazyList.Source`
has three concrete cases, `NavHost.Tab`, `TextField`, `Checkbox`, `NativeSurface`,
`TreePatch`, `LiveEvent`, `Predicate`, `Validator`, plus the entire `Action` sealed
hierarchy. Generating exhaustive `Arb`s for every one of them is not done in this
change.

The chosen subset (`Text`, `Button`, `Image`, `AsyncImage`, `Column`, `Value`,
`UnknownUiNode`) covers:

* Every primary widget shape on the v0 wire (per `TASK_BREAKDOWN.md` M1.x).
* Both `Container` (`Column`) and `Leaf` branches of `UiNode`.
* The full `Value<T>` sealed hierarchy.
* The `Action` types used by `Button`.
* The `UnknownUiNode` decoder default — the third VISION non-negotiable.

## Open follow-ups

Property-test coverage TODOs (one PR per cluster):

* `LazyList.Source` (`Inline` / `Paged` / `Bound`).
* `NavHost` + `Tab` + `Destination.{Modal,TabSwitch,PopToRoot}`.
* `Action.Submit` (with `payload`, `policy`, `onSuccess`, `onError`) and `Action.Sequence` /
  `Action.When`.
* `FormWidgets` (`TextField`, `Checkbox`) with their `Validator` and `Keyboard` shapes.
* `NativeSurface` with arbitrary `Map<String, JsonElement>` payloads.
* `TreePatch.{Replace,Append,Remove}` and `LiveEvent`.
* `Predicate.{Eq,Not,Empty,All,Any}` recursive composition.

Each addition is independent and can land without a protocol change.

## Consequences

* `:protocol` commonTest now depends on `io.kotest:kotest-property` and
  `kotlinx.coroutines.test`. Both are test-only; the protocol's production
  dependency surface is unchanged (still just `kotlinx.serialization` +
  `kotlinx.collections.immutable`).
* The 15 new `@Test` methods add ~1s to `:protocol:jvmTest`. Acceptable.
* When a test fails, kotest reports the seed and shrinks to a minimal counter-example,
  which is materially better than the example-based tests' "all-or-nothing" pass/fail.
