---
title: A test mirror cannot be derived, only its declarations can
date: 2026-10-07
status: accepted
---

## Context

Three times in one session, a hand-maintained list next to something the machine already
knows drifted, and the drift shipped:

| The list | What it mirrors | How it broke |
|---|---|---|
| `SKIPPED_NON_TESTS` → five names in `test_kiwi_sync.py` | the scanner's own bookkeeping | `7582a27f` added a skip, the list did not, and the suite was red on a clean `origin/main` |
| `desktopPlatformModule()` | `PlatformModule.jvm.kt` | `dc7f1d5d` extracted the mirror from a copy predating the unit-of-work work; `UnitOfWork` and four more bindings vanished, silently |
| `supabase/migrations/*.sql` header | the function bodies in the same file | no check at all; the fingerprints were a measurement written down once |

The first and third are now derived — `unjustified_skips` re-derives each entry from the
file it names, and `check-supabase-schema-integrity.py` compares the header's function set
against the body's. The second is not, and the temptation to try is the reason this is
written down.

## Idea

Generate `desktopPlatformModule()` from `PlatformModule.jvm.kt`, the way the other two
mirrors are generated. The declarations are derivable — a `single<Foo>` in production is a
`single<Foo>` in the mirror, and the test already proves they agree.

## Decision

**Derive the declarations, keep the bodies by hand. Do not generate the file.**

## Rationale

The declarations are pure syntax and the machine can read them. The bodies are not: the
real module opens the user's database and DataStore, and the file's own KDoc says why that
cannot be done twice in a process — a second graph throws "multiple DataStores active for
the same file". A generated mirror would have to substitute a body for every real one, and
the substitution is the part nobody can check: `single<FileSystem> { JvmFileSystem() }` and
`single<FileSystem> { BrokenFileSystem() }` generate identically.

So the honest split is the one already in place:

- **Who is bound** — derived, in both directions, by `PlatformModuleMirrorTest`. This is
  what catches the real drift, because a binding the mirror lacks is a graph it cannot
  build.
- **What is bound** — hand-written, and reviewed by eye, because it is the only place a
  deliberately different implementation can live.

The gap that made the drift invisible was not the hand-written body; it was a missing
*direction* in the derived half. The DAO scan reads `get<AppDatabase>().(\w+)()` and so
sees DAOs only, and the platform check compared desktop against Android rather than
against the mirror — so a new non-DAO binding on desktop had no check at all. That is fixed,
and it is the fix that matters.

## Consequences

- `PlatformModuleMirrorTest` has three directions: DAOs (production → mirror), typed
  bindings (desktop → Android), and typed bindings (production → mirror). The third is the
  one that was missing.
- It compares **simple names, not the text as written**. Production binds
  `CalendarAppQueries` fully qualified and the mirror imports it; comparing raw text
  reported a difference that was only a difference of spelling.
- `desktopPlatformModule()` stays a flat, greppable list under `@Suppress("LongMethod")`.
  Splitting it across helper functions would satisfy a complexity rule and cost the thing
  the file exists for: a mirror a reader can hold in their head, and that
  `PlatformModuleMirrorTest` can compare against the real module as a whole.
- A future platform binding still has to be added to the mirror by hand — and will be
  caught. That is the property worth having; generating the file would hide the catch
  rather than remove the work.

## Links

- `2026-10-07-an-invariant-needs-a-fake-that-can-lie-or-a-test-that-reads-the-source` —
  the same shape one level up: the fake cannot testify, so the check reads the source.
- `2026-10-05-koin-w003-in-a-test-graph.md` — why the graph test resolves a mirror rather
  than the real module.