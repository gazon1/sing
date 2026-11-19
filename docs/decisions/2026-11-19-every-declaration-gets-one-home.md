---
title: "Every declaration gets one home"
date: 2026-11-19
status: open
tags: [architecture, tooling, testing]
---

# Every declaration gets one home

## Context

`easement check` was red with two violations, both in test helpers, and both
tracing back to one cause.

`shared/src/jvmTest/.../test/helpers/CoroutineDiagnostics.kt` declares
`object CoroutineDiagnostics`, and `desktopApp/src/jvmTest/.../test/helpers/CoroutineDiagnostics.kt`
declared `object DesktopCoroutineDiagnostics` — the desktopApp file's *name* did
not match the class inside it. The indexer names a declaration after the file
that holds it, so desktopApp looked like it declared a second
`com.singularity.todo.test.helpers.CoroutineDiagnostics`.

Two files in one package, in two components, declaring one name, is what the
violations were made of:

| Path | Rule |
|---|---|
| `shared/src/jvmTest/.../test/FailureContextExtension.kt:3` | `shared → desktopApp` import |
| `desktopApp/src/jvmTest/.../feature/flows/agenda/AgendaStateScenarioSupport.kt:7` | `desktopApp → shared → desktopApp` cycle |

The first is the false edge: `FailureContextExtension.kt` imports
`com.singularity.todo.test.helpers.CoroutineDiagnostics` for the copy in its
own component, but the resolver could pick desktopApp's file instead, and the
import then read as a dependency that the code does not have. The second is
that false edge closed into a cycle with the real `desktopApp → shared`
dependency the desktopApp test code has.

Two more collisions of the same shape showed up as *evidence gaps* rather than
violations: `com.singularity.todo.core.sync.SyncState` (core-database's
`SyncState.kt` declares `SyncStateEntity` and `SyncStateDao`; the index named
the file, and `shared` declares a `SyncState` of its own) and
`com.singularity.todo.test.fakes.FakeRemoteConfigCacheDao`, which existed
*twice in one module* — `private` in `commonMain`, `public` in `commonTest`.

## Idea

A name that is declared in two places is not a name. The duplication was
deliberate — a shared test-fixtures module for one object was judged not worth
the module complexity — but the *file name* was free to change, and it was the
file name the tooling keyed on. Giving each declaration a name of its own keeps
the duplication (which is the cheaper of the two options) while removing the
collision.

## Decision

**Each duplicated declaration gets a unique name, in the component that holds
it.**

- `desktopApp`'s copy becomes `DesktopCoroutineDiagnostics`
  (`DesktopCoroutineDiagnostics.kt`), and its test becomes
  `DesktopCoroutineDiagnosticsTest` (`DesktopCoroutineDiagnosticsTest.kt`).
  The class was already called `DesktopCoroutineDiagnostics`; only the file was
  stale.
- `shared`'s copy keeps the name `CoroutineDiagnostics` and the file
  `CoroutineDiagnostics.kt`.
- The private `FakeRemoteConfigCacheDao` in `shared/commonMain` becomes
  `InMemoryRemoteConfigCacheDao`, matching the `InMemory*` idiom already used
  in this repository for in-memory port implementations (`InMemoryTaskDao`,
  `InMemoryStore`, `InMemorySyncPrefs`). The public
  `FakeRemoteConfigCacheDao` in `shared/commonTest` keeps the name — it is the
  fixture the tests import.
- core-database's `core/sync/SyncState.kt` is renamed to `SyncStateEntity.kt`,
  after what it actually declares.

**`easement.yml` is not edited, no baseline is regenerated, and no
`easement:disable` is added.** The fix is in the code, and the gate stays as
strict as it was.

The tool itself — where the gate is taken from, and which version it runs —
is a separate decision, recorded alongside this one.

## Rationale

Renaming was preferred over moving the code. Consolidating the two copies into
one shared test-fixtures module would remove the duplication for good, but it
needs build-file changes — `desktopApp`'s `jvmTest` does not consume `shared`'s
`jvmTest` output today — and it would trade a name collision for a module
boundary, which is a much larger decision than the problem asks for. The ADR
`2026-10-03-kotlinx-coroutines-debug.md` already records the duplication as
intentional.

Renaming was also preferred over waiting for the tool. The false edge was an
upstream bug, and upstream fixed it (#114); but a tool that reads a file name as
a declaration will keep finding *real* collisions — two components with one
filename — and the tree has two of them. Fixing the code makes the tree correct
under every version of the tool, including the one CI runs.

The cycle itself was a tool artefact, and it is worth being exact about that,
because the two questions have different answers. Decomposing the reported
cycle shows 205 evidence lines on the `desktopApp → shared` leg (real imports,
and `easement.yml` allows that direction) and exactly **one** on the
`shared → desktopApp` leg: the import in `FailureContextExtension.kt`, which
resolves to a class in `shared` itself. No code depended on desktopApp from
shared; the edge was the resolver choosing the wrong copy of a name declared
twice. The measurement that settles it: on the *same* pre-fix tree, the fork's
0.5.1 — which carries the #114 fix — reports `0 violation(s)`, while upstream's
0.9.0 reports the cycle. Upstream merged #114 (released in 0.6.4), which removed
the `shared → desktopApp` *dependency* violation, but its cycle detection still
carries the artefact. So the cycle was a false positive, and the collision
underneath it was real.

**Why upstream's fix did not remove the cycle.** #114 has two halves, and only
one of them is the resolution. The resolution half takes an import of a name
that several places declare for the copy in the importing file's component —
that is what removed the `shared → desktopApp` dependency violation, in the
fork and upstream alike. The second half is in the graph: for a dependency that
is still ambiguous, upstream *keeps an edge to every other candidate*, so that
a path through a copy the import might mean is not lost from the graph
(`src/check.rs`, v0.9.0: "Over-approximation for ambiguous/same-scope deps:
contribute an edge for every other candidate target so potential paths are not
lost"). The report's own chain shows the surviving edge: hop 1 is the real
`desktopApp → shared` import, hop 2 is `FailureContextExtension.kt:3`,
`shared → desktopApp`. Upstream is being conservative on purpose — the import
really could mean the desktopApp copy, so a cycle through it is a cycle it
refuses to rule out.

The fork went further than upstream's #114, and that is the whole difference
between the two verdicts: its diff against upstream 0.5.1 is much larger than
one resolver line (`src/lang/kotlin.rs` +158/−21, `src/lang/swift.rs` +101/−14,
`src/components.rs` +42), and its rule is stricter — "a name that two
components declare is taken for the copy in the component of the file that
imports it, **and never for the copy of another component**, whichever file the
reader saw first". The other candidates are dropped, not kept as edges.

So upstream did not miss a fix: given an ambiguous name, its answer is
defensible, and the tree really did have one. The only way to make the verdict
unambiguous under upstream's semantics is to have one declaration — which is
what the renames do. With one candidate, there is no over-approximated edge,
the import resolves unambiguously, and the cycle has nowhere to come from.

The `FakeRemoteConfigCacheDao` collision was not only a naming problem. Both
copies compile to the same binary name
`com/singularity/todo/test/fakes/FakeRemoteConfigCacheDao.class`, and
`shared`'s `commonMain` output and `commonTest` output sit on the same test
runtime classpath. Whichever copy the class loader picked first would be the
one that answered for both, and the two have different constructor signatures —
a `NoSuchMethodError` waiting for a classpath order to change. Two names remove
the hazard.

## Consequences

- `easement check` reports 0 violations and 0 declaration gaps on 0.9.0, and
  0 violations on the fork's 0.5.1 — the tree passes under the tool CI runs and
  under the tool a laptop runs.
- The `desktopApp ↔ shared` cycle is gone at its cause, not by narrowing the
  rules: `FailureContextExtension.kt` still imports
  `com.singularity.todo.test.helpers.CoroutineDiagnostics`, and that import now
  unambiguously resolves to the copy in its own component.
- `config/docs/flaky-baseline.txt` carries one entry per coroutine-diagnostics
  class now: the shared `CoroutineDiagnosticsTest` and the desktopApp
  `DesktopCoroutineDiagnosticsTest`. The observation in the baseline was made on
  the desktopApp class (four cases); the shared class (two cases) calls the same
  agent, so the entry is kept for both. The acknowledgement is unchanged in
  substance — the wait is the agent's, not the test's.
- Two names in `shared` changed: `FakeAppDatabase`'s private
  `FakeRemoteConfigCacheDao` is now `InMemoryRemoteConfigCacheDao`, and
  core-database's `core/sync/SyncState.kt` is now `core/sync/SyncStateEntity.kt`.
  Both are internal to their modules.
- A developer who upgrades to an upstream `easement` older than 0.6.4 still
  sees the tree as clean; the fix is in the code, so the tool's version only
  decides how much detail it reports.

## Links

- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopCoroutineDiagnostics.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopCoroutineDiagnosticsTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/test/helpers/CoroutineDiagnostics.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/test/FailureContextExtension.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeAppDatabase.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/test/fakes/FakeRemoteConfigCacheDao.kt`
- `core-database/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncStateEntity.kt`
- `config/docs/flaky-baseline.txt`
- `2026-10-03-kotlinx-coroutines-debug.md` — the ADR that records the duplication as intentional
- [mokevnin/easement#114](https://github.com/mokevnin/easement/pull/114) —
  the upstream pull request that fixed the resolver's choice of copy
