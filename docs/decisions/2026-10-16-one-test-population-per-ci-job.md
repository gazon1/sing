---
status: accepted
date: 2026-10-16
---

# One test population per CI job, enforced by a gate

## Context

Run `38933517902` (PR #684, merged as `15872650`) failed in a step its diff never
touched: `Normalise desktop results into the scenario matrix`, exit 2,
"declared target produced zero testcases" — for a target that had just been
measured. `check-test-runs`, three steps earlier, had counted
`desktopApp:test: 44 classes, 135 tests, 0 skipped`.

Both numbers were true at the moment they were taken. Between them sat four Gradle
invocations, and the first one whose test task was out of date — the **advisory**
`:koverMarkdownTable` step, the one carrying `|| true` — re-ran `:desktopApp:test`.
It re-ran it **without** `-Ptest.tags`, and every module's `useJUnitPlatform`
block maps an absent `-Ptest.tags` to `excludeTags("slow")`:

```kotlin
// desktopApp/build.gradle.kts, shared/build.gradle.kts,
// mcp-server/build.gradle.kts, adr-cli/build.gradle.kts
tags.isEmpty() -> excludeTags("slow")
```

So the `@Tag("slow")` scenario classes were *skipped, not executed*. The suite
stayed green, the count stayed at 135 because it had already been taken, and the
directory the normaliser was handed contained no scenario testcase at all.

## Idea

Two properties are available, and only one of them is the defect.

**Ordering** — normalise immediately after the test run, so the input is the test
run's own output rather than a race with later steps. Fixes this instance; the
trap reopens the moment anyone inserts a Gradle step there, and nothing says so.

**Parity** — every Gradle invocation in a job that can run tests selects the same
population. Holds whichever invocation ends up executing the task, which is
Gradle's up-to-date check to decide. `ci.yml` cannot be reordered into determinism,
so parity is the property that survives either outcome.

## Decision

Both, plus a gate for the second.

- `ci.yml`'s `tests` job: `Normalise desktop results` and `Render result matrix`
  moved directly after `Executed test counts`.
- `ci.yml`'s `tests` job: `-Ptest.tags=fast,slow` on every Gradle invocation —
  `:koverVerify`, `:koverMarkdownTable`, both `:koverLog`,
  `:detekt-rules:koverVerify`. This is the part that is **not** redundancy: Kover's
  aggregate tasks read the binary reports the test tasks produce, so they are test
  runs, and without the selector a stale one re-measures a narrower population.
- `scripts/check-gradle-test-population.py`, registered blocking as
  "gradle invocations keep one test population". It compares the declared selector
  of every test-capable Gradle invocation per job, and across `check.sh`.

The rule is **parity, not "always declare a selector"**. `check.sh` runs all nine of
its test invocations with no selector, on purpose: the local cycle is a fast cycle
and Gradle's default is the documented local behaviour. Demanding a selector there
would fail a tree that is correct, so those runs are reported as `note:` lines — the
fact stays visible without being dressed as a violation.

Nor is the rule "must be a test run before anything reads the results". That is
ordering, it is unenforced by a static check, and the ordering fix is what covers
it.

## Rationale

A gate is the only part of this that survives a year. The two workflow edits are
one-line facts in a diff; the gate is the statement that they are not optional, and
it has the same property the project's measurement work is built on — it fails on
a tree nobody has broken, at the moment somebody adds a fourth Gradle step.

The gate's positive control is the finding itself: it drops the selector from one
`:koverVerify` step in `ci.yml` and asserts the report names the surface and both
invocations. Without that control this would be a gate that cannot fail, which
`check-gate-wiring.py` Part J exists to reject.

The gate is also honest about its own reach. It does not run Gradle, so it cannot
say whether a task *would* re-run; it compares declared intent, which is the part a
reviewer cannot see in a diff and a `|| true` cannot announce.

## Consequences

- `ci.yml`'s advisory coverage steps can no longer silently narrow what the
  traceability gate reads. If they ever do, the population gate fails in the
  `static` job, in about a second, before the 30-minute JVM job.
- `flake.yml` needed no change and that is a measured result, not an assumption:
  its `run` job has exactly one Gradle invocation. Worth recording, because the
  comment at `flake.yml:110` already carries this reasoning and the workflow looks,
  by inspection, like `ci.yml` did.
- `e2e.yml` and `android-device-tests.yml` each have one Gradle invocation, neither
  test-capable in the sense the gate tracks beyond their own run. No change.
- Adding a second test-capable Gradle step to a job is now a diff that fails a gate
  until the selector is copied. That friction is the point.
- The unit tests for the gate assert on what it *saw*, not on its exit code: an
  empty finding list is exactly what a broken parser looks like, and it reads as a
  pass.

## Links

- Run: <https://github.com/gazon1/sing/actions/runs/38933517902>
- PR #684 (`15872650`) — the change this was found while validating
- [2026-10-16-ci-gradle-step-cannot-fail.md](2026-10-16-ci-gradle-step-cannot-fail.md) —
  the parent finding: a Gradle step whose failures could not be reported at all
- [2026-10-04-measurement-integrity.md](2026-10-04-measurement-integrity.md) — the
  principle: an unexecuted measurement is worse than none
- [2026-10-05-test-task-external-inputs.md](2026-10-05-test-task-external-inputs.md) — the sibling
  hazard, on the other side of the same boundary (Gradle deciding staleness for
  input the build file never declared)
- `openspec/specs/test-execution-integrity/spec.md` REQ-17