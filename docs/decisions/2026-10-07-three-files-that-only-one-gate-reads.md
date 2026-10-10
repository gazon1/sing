---
title: Three files, each read by exactly one gate, each silently broken on `main`
date: 2026-10-07
status: accepted
slug: three-files-that-only-one-gate-reads
---

# Three files, each read by exactly one gate, each silently broken on `main`

While pushing the Google-sync work I fixed three defects in a row. None of them was mine, none
of them was a logic error, and none of them would have been caught by anything an agent runs
by default. All three had the same shape.

| File | Only reader | How it broke |
|---|---|---|
| `config/detekt/detekt-rules-module.yml` | `:detekt-rules:detekt` | two branches each added `Filename:` to the same `ktlint:` mapping; YAML rejected the duplicate at parse time |
| `Maestro/TAGS.md` | `TestTagsCatalogJvmTest` | `TestTags.kt` gained `Pomodoro.TASKS_UNSUPPORTED`, the golden row never followed |
| `DesktopPlatformGraph.kt` | `KoinGraphValidationTest`, `PlatformModuleMirrorTest` | #218 moved the mirror and dropped `single<UnitOfWork>`; the failure then named `TaskRepository`, three levels above the missing binding |

## The common shape

Each file is a **single-consumer contract with no producer check**. The writer edits one side;
the reader compares or parses; nothing makes the writer's edit illegal at the time it is made.

- A YAML mapping accepts a second `Filename:` from git's point of view. Only SnakeYAML objects.
- Adding a test tag is a one-line Kotlin edit. Only the golden test knows a row is owed.
- Moving a Koin module between files is a pure refactor to every tool that does not resolve it.
  Koin resolves lazily, so the missing binding surfaces at first `get()`, not at definition.

What each one has in common is that **the failure is loud once the reader runs, and silent
until then**. Under the load this machine runs at — 8 to 20 minutes for a full gate — the
expensive read is the one that gets skipped or batched, so the defect ships.

## Why the detekt one is the worst of the three

`detekt-rules-module.yml` failing to parse took out the lint config for that *module* only.
`:shared` and `:desktopApp` keep their own, so a run of the two most-used detekt targets passed
and reported zero findings — which reads as clean. A gate that is unreachable is worse than a
gate that is absent: it looks like coverage.

## Decision

Treat "which gate reads this file, and does that gate run in the loop?" as a question to ask of
any new shared config or golden, at the moment it is introduced. Concretely:

- **A golden file gets its regeneration command in the failure message**, which
  `TestTagsCatalogJvmTest` already does. Keep that.
- **A mirror of a production module is a contract, so it moves with a check on it.** The
  `PlatformModuleMirrorTest` that #218 added is the right shape; the binding it failed to
  carry is why the comment on it says what moving it again costs.
- **Parse-check every YAML the gates read, in the gate self-tests.** Not done here — a
  duplicate-key check needs no Gradle and runs in seconds, and it is the only one of the three
  that could have fired before the merge rather than after it. Proposed, not shipped: an
  unreferenced script is exactly the unwired surface this repository audits against.

This is the `androidMain` argument from
[`2026-10-07-android-maintenance-needs-a-ci-gate.md`](2026-10-07-android-maintenance-needs-a-ci-gate.md)
one level up: that ADR is about a source set with no compiler in the loop, this one is about
config and goldens with no validator in the loop. Both come from CI being unable to start a
job, and both would be substantially mitigated by it.

## Consequences

- Three one-to-few-line fixes, each with the reasoning in its own commit message.
- The duplicate-key check is worth keeping as a script; it needs no Gradle and it is the only
  one of the three that could have run before the merge rather than after it.
- Not fixed here: nothing enforces that a mirror and its source stay in step at commit time.
  That is what `PlatformModuleMirrorTest` is for, and this commit strengthens it by restoring
  the binding it lost.

## Links

- `2026-10-07-android-maintenance-needs-a-ci-gate.md` — the same failure mode, one layer up
- `2026-10-06-google-sync-failures-were-shaped-like-skips.md` — code that reported something
  other than what it did; these three report nothing at all, which is the same disease at the
  configuration layer
