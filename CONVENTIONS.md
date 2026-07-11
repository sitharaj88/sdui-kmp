# Conventions

Rules that apply to every line of code in this repository. Many are non-negotiable and enforced by CI. Others are strong conventions enforced by review.

## Kotlin style

- **Explicit API mode is on** in every library module. Public types need explicit visibility.
- **No wildcard imports.** Detekt rule.
- **ktlint formatting.** Auto-applied on pre-commit hook.
- **Prefer data classes and value classes for IDs.** A `String` screen id is a bug. A `ScreenId` value class is correct.
- **Sealed hierarchies over enums when payload differs.** Enums for pure categorical choices; sealed for variant data.
- **Never swallow exceptions.** Handle, rethrow, or log via telemetry.
- **`internal` is the default visibility for implementation detail.** `public` is a deliberate choice.

## Protocol discipline

These rules apply to `:protocol` specifically. The schema linter enforces most of them.

- **Every `@Serializable` sealed interface has `classDiscriminator = "type"`.** We document the discriminator explicitly; we never let it default.
- **Every concrete sealed subclass has an explicit `@SerialName`.** Renaming a Kotlin class must not change the wire format.
- **Every field has either a default value or explicit nullability.** A required-without-default field cannot be added after v1.
- **No field is ever removed.** Mark `@Deprecated("since = "v2.3", level = WARNING)` and stop emitting server-side, but leave the definition.
- **No enum case is ever removed.** Same reason.
- **No field type change.** `Int` to `Long` is a breaking change even if values fit.
- **No nullability tightening.** `String?` never becomes `String`.
- **Every new node type has `since: SchemaVersion` reflecting the version it was introduced.** Once committed, never changed.
- **Every new node type has a thoughtful `fallback`.** "Empty text" is fine. "Nothing" is also fine, but must be a deliberate choice documented in the PR description.
- **No literal colors, sizes, or fonts.** Tokens only.

## Module rules

- **A module's public API is documented.** Every public type has a KDoc comment explaining its purpose. Missing KDoc on a public declaration fails Detekt.
- **`protocol` has no dependencies on third-party libraries except `kotlinx.serialization` and `kotlinx.collections.immutable`.** Adding a dependency here requires a design review.
- **Dependency direction is enforced.** See `docs/ARCHITECTURE.md` for the rules. `./gradlew verifyDependencyRules` checks them.
- **No cross-widget dependencies.** If `widgets-forms` needs something from `widgets-core`, that something belongs in `runtime`.

## Testing

- **Every public type in `:protocol` has a serialization round-trip test.**
- **Every widget has at least one golden snapshot test.**
- **Every `NodeRenderer` has a unit test verifying it renders from a fixture JSON.**
- **Every bug fix includes a regression test.**
- **Test names describe the behavior, not the method.** `decodes_unknown_node_to_fallback_without_throwing` is correct. `testDecode_1` is not.

## Commits and PRs

- **Commit message format:** `M1.3: add Value and Action sealed hierarchies`. Milestone number prefix is required for roadmap-driven commits; bug fixes use `fix: …` or `chore: …`.
- **PRs link to the task they complete.** Task id goes in the PR title and the PR description links to `docs/TASK_BREAKDOWN.md`.
- **PR description lists which acceptance criteria are met.** Checkboxes, not prose.
- **No PR changes more than one milestone's worth of tasks.** If a change spans milestones, split it.
- **PRs touching `:protocol` get extra scrutiny.** Minimum two reviewers, both of whom have read the full `docs/VISION.md` and `docs/ARCHITECTURE.md`.

## Versioning

- **Protocol snapshots are committed at every release tag.** File: `protocol-snapshot.json`.
- **Minor version bumps for additive changes.** `v1.2.0` → `v1.3.0`.
- **Major version bumps are last-resort emergencies.** They require parallel serving of both versions for at least six months.
- **Client app versions are independent of protocol versions.** A client on protocol v1.2 can live forever as long as the server still emits v1.2-compatible trees.

## Comments

- **KDoc on every public type.** Explain the purpose, not the implementation.
- **No comments describing what the code does line-by-line.** Good code explains itself.
- **Comments describing why are valuable.** A subtle invariant, a non-obvious trade-off, a workaround for a library bug — always document these.
- **`TODO` comments are banned in merged code.** Use GitHub issues instead.
- **`HACK` comments include the reason and a link to the issue tracking removal.**

## Forbidden patterns

- **Global mutable state.** Use composition locals, dependency injection, or explicit passing.
- **Reflection-based dispatch.** Every dispatch is compile-time typed.
- **Runtime type checks inside the rendering hot path.** If you find yourself writing `if (node is X)` inside a renderer, the registry is wrong.
- **String-typed paths (`"user.profile.name"` as a raw string).** Use `StatePath` — always.
- **`lateinit` for framework types.** Initialization order is a design concern, not a convenience.
- **`runBlocking` anywhere except in JVM `main` functions.** Coroutines all the way.

## When in doubt

If you cannot find the answer in this document, check `docs/ARCHITECTURE.md`. If it is not there either, ask — and then update one of the two documents with the answer so the next person does not have to ask.
