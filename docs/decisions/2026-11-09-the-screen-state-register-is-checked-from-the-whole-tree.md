---
title: The screen-state register is checked from the whole tree
date: 2026-11-09
status: accepted
tags: [tooling, gates, konsist]
---

# The screen-state register is checked from the whole tree

## Context

`2026-11-09-a-gate-moves-only-with-its-guarantee` kept `screen state collections`
on Python and wrote the entry condition: the gate has two directions, and the
second — a register entry whose property is gone — needs the whole tree, so a
per-file Detekt rule cannot hold it. The Python gate read five production source
roots with a hand-written line scanner.

## Idea

The repository already has a whole-tree reader: Konsist, in the architecture
suite. A Konsist test can see every declaration in every root and still read the
register file as data, so both directions stay in one check without a second
register.

## Decision

- `shared/src/jvmTest/…/arch/ScreenStateCollectionsTest.kt` is the gate. It runs
  in `:shared:archTest` and reads the same five roots the script read:
  `shared`'s `commonMain` / `androidMain` / `jvmMain`, `androidApp/src/main` and
  `desktopApp/src/main`.
- `config/compose/screen_state_collection_debt.conf` stays the register, and it
  is read as the file it is. The test holds no copy of the entries, so the file
  keeps its `properties = N` ratchet and its comment block.
- It checks the same three facts: a raw collection the register does not name, a
  register entry the code no longer has, and a recorded size that does not match
  the register.
- The Python script, its `Control`, its registry entry, its placement and its
  unit test are deleted; `scripts/ci/static-gates.sh` and
  `docs/scripts/gate-registry.md` are rendered without it.
- The scope says **holds**: a computed property (`val allEntries get() = …`) is
  not a second collection, it reads one the state already carries. Computed
  properties are skipped.

## Rationale

The differential control was run before the script was removed. The same
sabotage — a body property added to `SavedAgendaListState.Loaded` plus a bogus
register entry — made the script and the test name the same two keys.

The test also found a property the script had never seen: the logbook state's
computed `allEntries`. The script's parser stops at the first nested declaration
of a body, so every property declared after a variant inside a sealed interface
was invisible to it — a false negative in the direction that matters least for
the ratchet and most for the promise. Kept as a false negative, it would have
made the two checks disagree on the tree the moment the property stopped being
computed.

## Consequences

- The gate now runs with the architecture suite (`:shared:archTest`) instead of
  the static suite. That is later — the arch suite is a JVM task — and it is the
  schedule the other whole-tree gates already have.
- The registry has 88 entries and the shell registry 66 gate lines.
- Adding a screen state that holds a raw collection fails the arch test with the
  register's own wording; paying a debt without lowering `properties` fails too.
- A new declaration shape (a property with a getter) is skipped by design, and
  the test's KDoc says why.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ScreenStateCollectionsTest.kt`
- `config/compose/screen_state_collection_debt.conf`
- `docs/architecture/compose-stability.md`
- ADR `2026-11-09-a-gate-moves-only-with-its-guarantee.md`
- ADR `2026-10-27-a-screen-state-does-not-hold-a-raw-collection.md`
