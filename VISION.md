# Vision

## What we are building

`sdui-kmp` is a server-driven UI framework for Kotlin Multiplatform. Servers emit typed UI trees. Clients render them with Compose Multiplatform. The protocol is shared, versioned, and designed to survive a decade of evolution without breaking older clients.

## Why this exists

Mobile release cycles are slow. Experiments require app updates. Designers wait on engineers. Every SDUI system ever built solves these problems — and most of them rot within two years because schema evolution was an afterthought. We are building the one that does not rot.

## The five non-negotiables

These are the design commitments that every decision must respect. If a proposed feature conflicts with one of these, the feature loses.

### 1. One protocol, both sides

Server DSL and client renderer use the **same sealed Kotlin hierarchy** from the same `protocol` module. No IDL. No code generation. No JSON schema translated by hand. If the server can produce it, the client can parse it — by construction.

### 2. Additive-only evolution

Fields are added, never removed. Enum cases are added, never repurposed. Node types are added, never changed. Deprecation happens over multiple releases with telemetry-driven confidence, not code review vigilance. A schema linter enforces this in CI — violations fail the build.

### 3. The client never crashes on an unknown node

Every node declares `since: SchemaVersion` and optional `fallback: UiNode`. A client receiving a node it does not recognize renders the fallback (or nothing), never throws. This is the clause that lets us ship new widgets for 10 years without forcing app updates.

### 4. Actions are data, not code

A button carries an `Action` data class, not a lambda. This enables: offline action queues, optimistic updates, retry policies, idempotency keys, analytics instrumentation, and replay for debugging. A single `ActionHandler` interface is the one seam where host apps plug in.

### 5. Semantic design tokens, never literal values

The protocol carries `ColorToken.Surface`, not `#FFFFFF`. `Spacing.Md`, not `16.dp`. `TextStyle.Heading`, not `fontSize: 24`. Rebranding ships as a client release, not a protocol change. This decoupling is the difference between a framework that ages well and one that ages into a skin.

## Explicit non-goals

These are things we are deliberately **not** building. If someone asks, the answer is no, with the reason captured here.

- **A custom expression language.** Bindings are path lookups plus a tiny fixed set of predicates. No JSONLogic, no JEXL, no homegrown mini-DSL. If the server needs logic, it runs on the server.
- **Client-side scripting.** No JavaScript payloads, no Wasm plugins, no downloadable behavior. This would destroy the dumb-renderer invariant.
- **Animation keyframes in the protocol.** The protocol declares intent (`appearance: FadeIn`). The client owns timing and curves.
- **Literal colors, fonts, or sizes over the wire.** Tokens only.
- **GraphQL or gRPC.** Plain HTTP plus JSON. Boring, cacheable, still here in 2036.
- **A theming engine.** Theming is a client concern. The protocol knows about tokens.
- **Backwards-incompatible protocol changes without a deprecation window.** Major version bumps are reserved for emergencies and require parallel serving of both versions for at least six months.

## Target platforms

One protocol module, served from Kotlin/JVM backends, rendered on:

- Android (Compose)
- iOS (Compose Multiplatform)
- Desktop: macOS, Windows, Linux (JVM + Compose)
- Web (Wasm + Compose)

All five targets are first-class from day one. No target gets features the others do not.

## The longevity test

Every proposed feature must pass this question: **"If this ships and we never touch it again for ten years, does the framework still work?"** If the answer is no, the feature needs to be redesigned until the answer is yes.
