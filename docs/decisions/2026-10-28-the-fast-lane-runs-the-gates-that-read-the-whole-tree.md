---
title: The fast lane runs the gates that read the whole tree
status: accepted
date: 2026-10-28
tags: [testing, tools, gates, performance]
---

# The fast lane runs the gates that read the whole tree

## Context

The map (`scripts/test-impact-map.py`) answers "which test classes can this
change break?" and it answers it with two rules: a **scope** owns its tests,
and a **mention** is a coupling. Both are name-shaped — they work on the
class and file names, not on what a test reads.

There is a third kind of test in this repository, and neither rule can see it:
a gate that **reads a whole source root**. `CommonMainJvmApiTest` walks every
file in `commonMain` looking for JVM-only API calls; `TestTagCoverageTest`
walks every test source directory; `ScopedWriteQueryIsolationTest` reads every
DAO query. A gate like that belongs to no scope, and it mentions a class only
in the sense that it reads every class — so a change to a file it never names
selects nothing.

That is not hypothetical, and the change that found it was this session's
own: replacing `java.util.UUID.randomUUID()` with the multiplatform
`nextId()` in `DataStoreSessionStore` broke `CommonMainJvmApiTest` — the gate
that forbids JVM-only APIs in `commonMain` — and the map did not select it.
The map was right about everything it said and silent about the one gate that
had an opinion about the change.

The cost of the silence is the whole point of the map. Its rationale says so:
"The dangerous failure is the narrow one: the map says 'nothing to run', the
developer believes it, and the change breaks something nobody looked at." A
map that skips a tree-wide gate is exactly that failure, reached by a narrow
map. The fast lane is the thing developers are told to trust when they want to
know quickly — so the gates it skips are the ones that will not be noticed
until CI, which is the moment the fast lane was supposed to save.

## Idea

Name the third rule: a test that reads a whole source root can break because
of **any** change in that root, so any change selects it.

## Decision

**A test that reads a whole source root is selected by every change to the
tree it reads.**

The arch suite is the tree's own suite: its gates decide what the tree is
allowed to contain, and each reads the tree through whatever happens to be
convenient —
`walkTopDown()`, `Files.walk`, `Konsist.scopeFromExternalDirectory`,
`SourceScan.productionFiles()`. So the DIRECTORY is the marker, and every gate
in it is selected by any change to a source tree. A list of calls was the first
version of this rule; it missed `DroppedResultIsReportedTest`
(`SourceScan.productionFiles()`), `SingleOfKotlinDefaultsTest` (Konsist) and
the `TextField*` scans — about ten fast gates, each of them reading the tree as
literally as the one the rule was written for.

Outside that suite the CALL is the marker, matched against the whole file
rather than the class body: a fixture-corpus test reads a directory without
living in `arch/`, and the walk usually sits in a helper above the class.

The rule is narrow in the other direction too: it fires for a change in a
source tree, not for a change in a document. And a changed TEST is in the map
twice over — it is a test that has to run, and it can break the gate that
reads the whole test tree (a gate that asserts every test class carries a
`@Tag` is broken by a test class that does not).

**A change to a tree a gate reads but the Kotlin rules never see selects that
gate too.** `MaestroFlowTagsTest` walks every flow and fails on one without
`tags:`; the Room gates read the committed schema exports. A `.yaml` or `.json`
change maps no test class by the scope and mention rules, so it selects the
tree-wide gates instead.

**And the run path of the map switches the coverage agent off.** A run of a
SUBSET of the suite measures a coverage number that describes the subset — a
number nobody can act on — and the agent multiplies the run by about 1.6 on
this tree (measured: 1241 s instrumented, 771 s not). The coverage job is
where the measurement belongs. The flag is added on the path that RUNS the
command; the path that PRINTS the command leaves the decision to its caller,
which is why the CI `quick` job adds the same flag to the command it
evaluates.

## Rationale

**Why the map, and not a new gate.** The map is already the thing that
answers "what does this change require?", and it is already trusted with the
answer. A gate that said "run the tree-wide gates too" would be a second
authority on the same question, and the two would disagree — the map would
say a change needs nothing while the gate said it needs fifty classes, and
the map is the one developers read.

**Why a tree-wide gate is selected by EVERY change, and not by a mention.**
A gate that reads the whole tree has no opinion about which file caused the
violation: `CommonMainJvmApiTest` reports the file and the line, not the
change. Asking "does this gate mention the changed class?" is the wrong
question for it, and the answer is usually no. The cost of the rule is that
the fast lane now runs more classes than it did: 68 tests are tree-wide in
this tree, of which 54 are in the fast population (35 before the rule covered
the whole arch suite). They are a few seconds each, and they are the gates
that were red on `main` while the map reported a clean selection.

**Why the coverage flag belongs on the run path and not on the printed
command.** `--command` prints the selection so a caller can run it; the
caller decides what to run it with, and the CI `quick` job already decides to
switch the agent off. `--run` has no caller to decide for it — it IS the
caller — so it decides for itself. Two spellings of one decision would drift.

## Consequences

- The map no longer reports a selection that omits a gate the change can
  break. The tree-wide gates are in the map for every change in the tree
  they read, and the reason each one is there says so.
- The fast lane runs more classes than before: 54 fast-tagged gates for a
  change in `commonMain`, against 35 when the rule named four calls. They are
  the cheapest classes in the suite, and they are the ones that catch a
  violation the moment it is written.
- `just tests::impact` no longer pays the Kover agent: the run path passes
  `-Pcoverage.enabled=false`, so the selection runs at the uninstrumented
  cost. The whole uninstrumented suite is 771 s on this tree; the selection is
  a fraction of it.
- The rule is a property of the place, not a list of calls: a new gate added
  to the arch suite is picked up without touching the map.

## Links

- `scripts/test-impact-map.py` — the map
- `scripts/tests/test_test_impact_map.py` — the map's own tests
- ADR `2026-10-22-the-test-cycle-has-a-map` — the rules this one completes
- ADR `2026-10-23-the-invariant-is-a-test-not-a-file` — why the gates stay
  Kotlin tests rather than becoming a Python registry
