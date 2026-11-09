---
title: "The suite does not name a task that runs no tests"
date: 2026-11-09
status: accepted
tags: ["ci", "gradle", "gates"]
---

# The suite does not name a task that runs no tests

## Context

`2026-10-07-a-gradle-task-named-by-one-surface.md` measured the Gradle surfaces
and recorded this:

> `:androidApp:test` appears in `ci.yml` but there is nothing behind it:
> `androidApp` has **no** unit-test source set, only four instrumentation
> classes under `androidTest/`. That task succeeds without executing a test. It
> is not fixed here — an empty task that reports success is worth its own
> decision — but it is recorded here because it was found while measuring this
> one.

The decision was deferred. The suite job's `Gradle (tests)` step still named
the task, and its comment explained why: the list was to stay a copy of
`koverReport`'s `dependsOn` list.

That reason had already stopped being true before this change. The two lists
have diverged: the suite job also runs `:shared:archTest`,
`:core-domain:jvmTest` and `:detekt-rules:test`, and the coverage job also
aggregates `:adr-core:jvmTest`, which the suite job does not name. The comment
asserted a copy that no longer existed.

## Idea

- Name the tasks that run tests, and stop naming the one that does not.
- Keep the task in the coverage job's list, where it is load-bearing.

## Decision

The suite job (`Gradle (tests)` in `.github/workflows/ci.yml`) and its local
mirror (`.just/ci/mod.just`) stop naming `:androidApp:test`. The row for it in
`GRADLE_TASK_PARITY` goes with them: the task is now named by no surface, so
there is no asymmetry left to declare.

The coverage job keeps it. `koverReport`'s `dependsOn` list is the denominator
the coverage task-list gate reads, and that gate fails when a module with
tests is missing from the list — removing `:androidApp:test` from
`koverReport` while `androidApp`'s classes stay in the denominator makes the
gate silent about a module it must still score.

## Rationale

**A named task reads like a suite that exists.** `:androidApp:test` is
NO-SOURCE: `androidApp` has no `src/test/` at all, so the task succeeds
without executing a test. In the list of a job whose whole purpose is to run
the suite, it is the one entry that runs nothing — and it is the entry a
reader trusts, because it is spelled like the others.

**The "it stays a copy of kover's" reason was already false.** The comment
justified the entry with a list that had drifted. Keeping an entry alive on a
stale reason is how a task that runs nothing stays in a list of tasks that do.

**The coverage side is a different question.** There, the list is the
denominator, not a menu of suites to run: a project that is in the denominator
and has no test source set is the case the gate exists for. The task belongs
in that list and not in this one.

**The test now pins the absence.** `scripts/tests/test_ci_pr_path.py` asserts
that the suite job does not name `:androidApp:test`, so the empty task cannot
come back unnoticed — the same way the other names are pinned.

## Consequences

- The suite job's list contains only tasks that execute tests.
- `GRADLE_TASK_PARITY` loses a row: the task is named by no surface, and the
  parity check only judges a task that some surface names.
- `koverReport` is unchanged: the coverage denominator still says what it said.
- The instrumentation tier is untouched: `:androidApp:connectedDebugAndroidTest`
  still runs on a device in `android-device-tests.yml`.

## Links

- `docs/decisions/2026-10-07-a-gradle-task-named-by-one-surface.md` — the
  measurement that recorded the empty task and deferred this decision
- `.github/workflows/ci.yml` — the suite job and the coverage job
- `.just/ci/mod.just` — the local mirror of the suite job
- `scripts/src/sgate/commands/_registry.py` — `GRADLE_TASK_PARITY`
- `scripts/tests/test_ci_pr_path.py` — the assertion that pins the absence
