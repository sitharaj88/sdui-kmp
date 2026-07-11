# Claude Code — Kickoff Prompt

Paste the content below into Claude Code at the repository root. This is the message that starts development.

---

## The prompt

```
You are developing `sdui-kmp`, a server-driven UI framework for Kotlin Multiplatform, designed for a 5–10 year lifespan.

# Your reading assignment (do this first, in this order)

Before writing any code, read these documents completely. Do not skim.

1. `docs/VISION.md` — the five non-negotiables and explicit non-goals.
2. `docs/ARCHITECTURE.md` — the full technical design.
3. `docs/ROADMAP.md` — the ten milestones.
4. `docs/TASK_BREAKDOWN.md` — every task in M0–M2.
5. `docs/CONVENTIONS.md` — code style and module rules.
6. `docs/DEFINITION_OF_DONE.md` — the checklist for every task.
7. `docs/RISKS.md` — known risks and mitigations.

After reading, summarize back to me in your own words:
- The five non-negotiables.
- The module dependency rules.
- What "additive-only evolution" means in practice.
- Why `NativeSurface` is a protocol citizen and not an escape hatch.

Do not proceed until I confirm your summary is correct.

# Your working rules

- Work through tasks in strict order: M0.1 → M0.2 → ... → M0.5 → M1.1 → ... → M1.14 → M2.1 → ...
- One task = one PR. One PR = one task. Do not batch.
- Before starting each task:
  - Reread that task's acceptance criteria in `docs/TASK_BREAKDOWN.md`.
  - Check `docs/CONVENTIONS.md` for any relevant rules.
  - Check `docs/ARCHITECTURE.md` for the relevant subsystem design.
- For each task:
  - Write the code.
  - Write the tests that verify the acceptance criteria.
  - Run `./gradlew check` and fix everything it reports.
  - Run `./gradlew verifyDependencyRules`.
  - Update CHANGELOG.md with a one-line entry.
  - Commit with the format `M1.3: add Value and Action sealed hierarchies`.
- After each task, run the full Definition of Done checklist from `docs/DEFINITION_OF_DONE.md`. Do not mark the task complete until every box is checked.

# When you are stuck

Stop. Do not guess. Do not fabricate. Ask me one of these questions:

- "The architecture document says X but the task says Y. Which is correct?"
- "I cannot meet acceptance criterion N without violating convention M. Here are three options: ... Which do you prefer?"
- "This task requires a decision not captured in the docs: ... What should the answer be?"

I would rather you ask five clarifying questions than produce code I have to throw away.

# Protocol-specific red lines

These are non-negotiable. Violating any of these is a bug that must be reverted immediately:

1. Never remove a public type, field, `@SerialName`, or enum case from `:protocol`.
2. Never tighten nullability of an existing field.
3. Never change the type of an existing field.
4. Never add a required field without a default value.
5. Never put literal colors, pixel sizes, or font names into a widget field.
6. Never put a lambda in a data class. Actions are data.
7. Never write a client-side crash for an unknown node type. Always render the fallback or nothing.
8. Never add a dependency to `:protocol` beyond `kotlinx.serialization` and `kotlinx.collections.immutable` without a design review.

# Current state

The repository currently contains only this planning package. No code. No build files. Your first task is M0.1 (Gradle bootstrap).

# Start

Confirm you have read and understood all seven documents. Summarize the five non-negotiables, the module dependency rules, the meaning of additive-only evolution, and why `NativeSurface` is a protocol citizen. Then wait for my confirmation before starting M0.1.
```

---

## How to use this prompt

1. Clone the repository to a working directory.
2. Copy this planning package into the repo root (so `docs/` and `README.md` exist).
3. Open the repo in Claude Code.
4. Paste the prompt above as your first message.
5. Wait for the summary. Correct anything wrong before letting it proceed.
6. For each task, let Claude Code propose the plan, then approve it, then let it implement.
7. Review every PR before merge, especially PRs touching `:protocol`.

## What to do if Claude Code drifts

If at any point Claude Code:

- Starts batching tasks across milestones.
- Proposes removing a protocol type.
- Suggests skipping a test.
- Wants to "refactor later."
- Argues against a non-negotiable.

Stop the session. Paste this correction:

```
You are drifting from the plan. Re-read docs/VISION.md section "The five non-negotiables" and docs/DEFINITION_OF_DONE.md. Acknowledge the specific rule you were about to violate, then propose a compliant alternative.
```

This is how we keep a 10-year project on rails over thousands of sessions.
