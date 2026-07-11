# Migration guide

This document tracks every breaking change between published versions of `sdui-kmp`. Older
clients on protocol vN must continue to work as long as servers emit vN-compatible trees,
so most "migrations" are server-side; client migrations only matter when an additive
change becomes load-bearing for new features.

The format is the same as kotlinx.serialization's: one section per pair of adjacent
versions, each with explicit "what changed" and "what to do". Even a no-op release gets a
section so the table is uninterrupted history.

---

## v1.0.0 → v1.0.0 (initial release)

No previous version. This is the baseline.

**For server authors:**
- Treat the [protocol-snapshot.json](../protocol-snapshot.json) at this tag as the contract.
- Run `./gradlew verifyProtocolSnapshot` in CI; PRs that violate additive-only evolution
  fail with a structured violation list.

**For client app authors:**
- Pin one of the published versions of `:runtime`, `:widgets-*`, and `:transport-http`.
- Wire the four extension seams (widgets, native surfaces, telemetry, image loader) per
  [EXTENSION_GUIDE.md](EXTENSION_GUIDE.md).

---

## Template — copy this section verbatim for each new release

```
## v<old> → v<new>

**Summary:** one-paragraph description of the highlights.

### Protocol changes

#### Added (no migration required)
- `<Type>.<field>: <T>` — what it's for, when to use it.
- `<NewWidget>` (`@SerialName("…")`) — short pitch.
- `<EnumName>.<NewCase>` — short pitch.

#### Deprecated (still works; will be removed in v<future>)
- `<Type>.<field>` — replacement: `<other field or pattern>`.
  Server-side: stop emitting; the field stays decodable until v<future>.
  Client-side: rendering ignores it once you upgrade to v<new>.

#### Behavior changes (no source changes required, but observable)
- `<Type>` now does X instead of Y when condition Z holds. Concrete example:
  before/after JSON snippets.

### Runtime changes

#### Added APIs
- `dev.sdui.kmp.runtime.<Foo>` — what it does, when to use it.

#### Deprecated APIs
- `<old fn>` — replacement: `<new fn>`. Migration: search-and-replace where possible;
  document the cases that aren't search-and-replace.

#### Removed APIs
- (None — runtime APIs are deprecated for at least one minor before removal.)

### Convention plugin / build changes

- `<plugin>.<setting>` default changed from X to Y. Override with `<snippet>` if you need
  the old behavior.

### Migration script (optional)

If a mechanical refactor handles 80%+ of the migration, ship a runnable script that
performs it and link it here. Otherwise leave this section out.
```

---

## Discipline notes

Even if a release contains zero breaking changes, write a section for it. The motivations:

1. **The format matters more than the content.** Authors writing migration sections internalize
   "what would I be writing if I were breaking something?" — and that question often
   surfaces a change that *would* have been breaking but was avoidable.
2. **Auditing is easier than refactoring.** A future migration that drops support for an
   old protocol version needs a clear timeline of what changed when. The migration history
   is that timeline.
3. **Public adopters track these sections.** Skipping one for a "trivial" release teaches
   them to skip them too.

The schema linter (`:tooling-cli`) enforces additive evolution mechanically. This document
is the human-readable companion: what was added, why, and what (if anything) the consumer
has to do about it.
