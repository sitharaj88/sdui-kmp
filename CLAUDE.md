# CLAUDE.md

Project-level guidance for Claude Code. Loaded automatically into every session. Keep concise — link to the authoritative docs rather than duplicating them.

## What this repo is

`sdui-kmp` — a server-driven UI framework for Kotlin Multiplatform, designed for a 5–10 year lifespan. Servers emit typed, versioned UI trees; Compose Multiplatform clients render them. Protocol and renderer share the same `kotlinx.serialization`-backed sealed hierarchy.

Status: implemented framework (~30 modules) — protocol, runtime, widgets, transports (http/live/cache), server + studio-server (Postgres/RBAC/JWT/experiments), telemetry, tooling, and samples. Core JVM test suites pass. A production-readiness audit (2026-07-11) found it **not yet production-ready**: see [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) for the ranked blockers and path to production (top issues: secure-by-default fixed; forward-compat fallbacks, render recursion cap, and horizontal-scaling wiring remain).

## Authoritative documents (read in this order on first contact)

1. [VISION.md](VISION.md) — the five non-negotiables and explicit non-goals
2. [ARCHITECTURE.md](ARCHITECTURE.md) — module topology, protocol types, runtime, transport
3. [ROADMAP.md](ROADMAP.md) — milestones M0–M9
4. [TASK_BREAKDOWN.md](TASK_BREAKDOWN.md) — every M0–M2 task with acceptance criteria
5. [CONVENTIONS.md](CONVENTIONS.md) — code style, module rules, commit format
6. [DEFINITION_OF_DONE.md](DEFINITION_OF_DONE.md) — per-task checklist
7. [RISKS.md](RISKS.md) — known risks
8. [KICKOFF_PROMPT.md](KICKOFF_PROMPT.md) — the working-rules contract

If one of these contradicts guidance below, the document wins and this file should be updated.

## The five non-negotiables (from VISION.md)

1. **One protocol, both sides.** Server DSL and client renderer share the same sealed hierarchy in `:protocol`. No IDL, no codegen, no hand-translated schema.
2. **Additive-only evolution.** Fields added, never removed. Enum cases added, never repurposed. Node types added, never changed. Enforced by schema linter in CI.
3. **Client never crashes on unknown nodes.** Every node has `since: SchemaVersion` and optional `fallback: UiNode`. Unknown discriminator → render fallback (or nothing). Never throw.
4. **Actions are data, not code.** `Action` is a sealed data class, never a lambda. Enables offline queues, optimistic updates, retry, idempotency, replay.
5. **Semantic tokens only.** `ColorToken.Surface`, `Spacing.Md`, `TextStyleToken.Heading`. Never hex colors, pixel sizes, or font names on the wire.

## Protocol red lines (from KICKOFF_PROMPT.md)

Violating any of these is a bug that must be reverted immediately:

- Never remove a public type, field, `@SerialName`, or enum case from `:protocol`.
- Never tighten nullability of an existing field.
- Never change the type of an existing field.
- Never add a required field without a default value.
- Never put literal colors, pixel sizes, or font names into a widget field.
- Never put a lambda in a data class — actions are data.
- Never write a client-side crash for an unknown node type.
- Never add a dependency to `:protocol` beyond `kotlinx.serialization` and `kotlinx.collections.immutable` without a design review.

## Module dependency rules

From [ARCHITECTURE.md](ARCHITECTURE.md). Enforced by `./gradlew verifyDependencyRules` (M0.3):

- `:protocol` depends on nothing except `kotlinx.serialization` (+ `kotlinx.collections.immutable`).
- `:runtime` and `:server` both depend on `:protocol`. They never depend on each other.
- `widgets-*` modules depend on `:runtime` only. Never on each other.
- `transport-*` modules depend on `:runtime` plus their platform client.
- Samples depend on whatever they need from Tier 3 and below.

## Working rules

- **One task = one PR.** Do not batch across milestone boundaries.
- **Follow milestone order strictly.** M0.1 → M0.2 → ... → M1.1 → ... No skipping ahead.
- Before each task: reread its acceptance criteria in [TASK_BREAKDOWN.md](TASK_BREAKDOWN.md), then check [CONVENTIONS.md](CONVENTIONS.md) and the relevant [ARCHITECTURE.md](ARCHITECTURE.md) section.
- After each task: run `./gradlew check` and `./gradlew verifyDependencyRules`. Walk the full [DEFINITION_OF_DONE.md](DEFINITION_OF_DONE.md) checklist.
- **Commit format:** `M1.3: add Value and Action sealed hierarchies`. Milestone prefix required for roadmap work; otherwise `fix:` / `chore:`.
- **Explicit API mode is on** in every library module. Every public type needs KDoc.

## Stop-and-ask discipline

When a requirement is unclear, stop and ask — do not guess or fabricate. Example framings from [KICKOFF_PROMPT.md](KICKOFF_PROMPT.md):

- "The architecture document says X but the task says Y. Which is correct?"
- "I cannot meet acceptance criterion N without violating convention M. Options: ... Which do you prefer?"
- "This task requires a decision not captured in the docs: ... What should the answer be?"

Five clarifying questions beat a week of work on wrong assumptions.

## Explicit non-goals (do not build these)

From [VISION.md](VISION.md): custom expression language, client-side scripting, animation keyframes in the protocol, literal style values on the wire, GraphQL/gRPC, a theming engine, backwards-incompatible protocol changes without a deprecation window.

## Target platforms

Android, iOS, Desktop (JVM), Web (Wasm), Kotlin/JVM server — all from the same `:protocol` module. No target gets features the others do not.
