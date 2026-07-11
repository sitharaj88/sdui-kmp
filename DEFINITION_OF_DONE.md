# Definition of Done

A task is done when **every** item below is true. Not "mostly." Not "I'll do that next." Every item.

This exists because the difference between a framework that lasts a decade and one that rots in year two is whether the team holds this line under schedule pressure.

## For every task

- [ ] All acceptance criteria from `docs/TASK_BREAKDOWN.md` are met.
- [ ] Code compiles on all five targets (Android, iOS, Desktop, Wasm, JVM). No `expect/actual` stubs missing.
- [ ] `./gradlew check` passes locally.
- [ ] `./gradlew verifyDependencyRules` passes.
- [ ] CI is green on the PR.
- [ ] Every new public type has a KDoc comment.
- [ ] Every new public function has a KDoc comment with at least a one-line description.
- [ ] No `TODO`, `FIXME`, or `XXX` comments in merged code.
- [ ] Commit message follows the format in `docs/CONVENTIONS.md`.

## For tasks that touch `:protocol`

Additional requirements because protocol changes are forever.

- [ ] Every new type has an explicit `@SerialName`.
- [ ] Every new sealed subclass has a round-trip serialization test.
- [ ] Every new node type has a `since: SchemaVersion` reflecting the current release.
- [ ] Every new node type has a deliberate `fallback` (or documented reason for null).
- [ ] No existing field was removed, renamed, or retyped.
- [ ] No existing enum case was removed or renamed.
- [ ] Schema linter passes.
- [ ] `protocol-snapshot.json` is regenerated and committed if this is a release PR.

## For tasks that add a widget

- [ ] Widget is defined in `:protocol` with a stable `@SerialName`.
- [ ] `NodeRenderer` implementation exists in the appropriate `widgets-*` module.
- [ ] Widget accepts an `a11y: A11y? = null` parameter.
- [ ] Golden snapshot test covers at least the default appearance.
- [ ] Golden snapshot test covers at least one state-bound variant.
- [ ] Widget is added to `:protocol-fixtures` with a representative instance.
- [ ] `samples/sample-server` exposes a screen demonstrating the widget.

## For tasks that add a transport

- [ ] Transport implements the appropriate interface (`ScreenSource` or `LiveSource`).
- [ ] Transport has an end-to-end test against `sample-server`.
- [ ] Error handling covers: no network, server 4xx, server 5xx, malformed JSON.
- [ ] Transport works on all five platforms (or has documented per-platform variants with parity).

## For tasks that add a tooling feature

- [ ] Tool has a usage help message (`--help`).
- [ ] Tool has at least one integration test.
- [ ] Tool is invokable from Gradle (`./gradlew :tooling-xxx:run --args="..."`).
- [ ] Tool's purpose is documented in `tooling-xxx/README.md`.

## Before merging to main

- [ ] PR description references the task id from `docs/TASK_BREAKDOWN.md`.
- [ ] PR description lists which acceptance criteria are met (with checkboxes).
- [ ] Reviewer has run the change locally on at least one platform they do not normally develop on.
- [ ] No files added outside the repository's established structure.
- [ ] No new third-party dependencies without a line in the PR description explaining why.
- [ ] If this touches `:protocol`, a second reviewer who has read `docs/VISION.md` has also approved.

## Escalation

If a task cannot meet this definition without heroics, stop and raise it. Either:

- The task is bigger than the breakdown suggests — split it.
- The architecture is wrong — amend `docs/ARCHITECTURE.md` first, then implement.
- A convention is in the way — discuss whether the convention needs amending.

The definition of done is a floor, not a ceiling. Code that technically meets every checkbox but is brittle, unclear, or hard to maintain is still not done.
