---
title: A test task that reads a tree it does not declare reports a stale verdict
date: 2026-10-05
status: accepted
tags: [testing, gradle, ci, process, architecture]
---

## Context

Architecture tests in this repository do not only assert over compiled classes. They
walk the filesystem. `TestTagCoverageTest` reads four source trees, `MaestroFlowTagsTest`
reads every flow under `Maestro/flows`, `ViewModelTestCoverageTest` matches production
ViewModels against the test files that mention them, and `DetektConfigWiringTest` walks
up looking for `settings.gradle.kts`.

A file read at runtime is an input to the test task whether or not it feeds the
compiler. Gradle cannot see the difference: its up-to-date check hashes what the task
consumes through the build model, and a path opened inside a test is invisible to it.

## The failure, measured

On 2026-10-04 `MaestroFlowTagsTest` — which resolves the workspace root by walking up
four levels from `commonMain.root` and then reads `Maestro/flows/**` — was found not to
re-run when a flow changed. The probe, run twice with an identical command:

```
$ ./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'   # baseline
> Task :shared:jvmTest UP-TO-DATE
BUILD SUCCESSFUL

$ # inject a valid Maestro command carrying an unknown `id:` into a flow
$ ./gradlew :shared:jvmTest --tests '…MaestroFlowTagsTest'
> Task :shared:jvmTest UP-TO-DATE
BUILD SUCCESSFUL in 15s
```

A blocking gate, reporting success, about a file it had not re-read. Nothing in the
output distinguishes that from a clean run, which is what makes it the worst kind of gate
failure: the *stale answer looks like a pass*. A developer who fixes the flow, re-runs,
sees green, and ships a broken selector.

The same shape had already been fixed twice by hand in this repository — `desktopApp` and
`mcp-server` trees on `:shared:jvmTest`, and `config/detekt/detekt.yml` on
`:detekt-rules`, the latter with a comment describing this exact probe. Three hand-fixes
is the signal: fixing instances is not a strategy, it is a coincidence of who tripped
over what.

## Idea

Make the dependency explicit, then make the explicitness checkable.

A test that derives a path is worse than a test that receives one, independent of the
staleness. `MaestroFlowTagsTest`'s four-level walk produced a path that **no build file
mentioned** — so no static check could see it, and Gradle certainly could not. Passing
`maestro.root` in as a system property makes the dependency a sentence in a build file,
which is a thing a program can read.

## Decision

1. `Maestro/` is declared as an `inputs.dir` of `:shared:jvmTest`, and its path is passed
   to the test as `maestro.root` instead of being derived.
2. `scripts/check-test-task-inputs.py` fails when a path-valued `systemProperty` resolves
   outside the module that declares it and no `inputs.dir`/`inputs.file` covers that path
   or an ancestor of it. Wired into `check.sh` and into `ci.yml`, blocking.
3. The gate refuses to pass when it found no path-valued properties at all.

Point 3 is the gate's own version of the defect it exists to prevent. The first version
of the parser matched the path expression up to the first `)`, which is the one closing
`layout.projectDirectory.dir("…")` — so it resolved nothing, skipped every root, printed
`OK`, and had checked nothing. The second version keyed the rule on property names ending
in `.root`, and a unit-test fixture named `maestroRoot` sailed through because the gate
did not recognise it as a root at all. Both bugs produced a clean result, which is the
signature of a check that cannot fail. The counter and the name-agnostic rule exist
because of them.

## Rationale

The rule triggers on a path *escaping its module*, not on external roots existing. A root
inside the module is already a compile input; a root outside is a dependency only Gradle
cannot see, which is exactly the kind that goes missing. An ancestor directory satisfies
the rule, so declaring `../Maestro` covers a root pointing at `../Maestro/flows` — the
common case, and the one that would otherwise make the gate fussy about how a path was
spelled.

The property-name rule was dropped rather than loosened. Matching `*.root` was a naming
convention, and a gate whose rule is a convention has a hole shaped like that convention.
The value escaping the module is the actual invariant, and it holds for any name.

**Known blind spot, stated rather than implied.** The gate sees declared roots. A test
that walks up from a root to reach a tree nobody declared — the state this repository was
in for `Maestro/` — is invisible to it by construction. That is why decision 1 exists: the
structural fix, not the check, is what closed this instance. The check makes the closed
state durable, which is the part a check can do; it could not have found the open state.

## Consequences

- `:shared:jvmTest` re-runs when a Maestro flow changes, and `MaestroFlowTagsTest` fails on
  an unknown selector. Verified by probe in both directions: the same injection that
  produced `UP-TO-DATE` / `BUILD SUCCESSFUL` before the fix produces a failing test after
  it, and removing the `inputs.dir` makes the new gate fail.
- One more external dependency in `:shared:jvmTest` means a flow edit re-runs the shared
  suite. That is the correct trade: the alternative is a gate that lies.
- Two properties on `:shared:jvmTest` now assert the same invariant by different means —
  `TestTagCoverageTest` at runtime, `check-test-task-inputs.py` at build time. They overlap
  on purpose; the runtime one catches a class of *content* the build-time one cannot see,
  and the build-time one holds even when no test runs.
- A future test reading a new tree has one obvious way to declare it. The error message
  says so and tells the author to probe the fix, because an unprobed input declaration is
  a comment.

## Disproved along the way

`DetektConfigWiringTest` reads `config/detekt/detekt.yml` and declares no system property
at all, which looked like a second instance of the same defect. It is not: `main` had
already declared that input, with a comment describing the identical probe, and the control
run confirmed it — stashing the build file and editing the config still re-ran the task.

The input declaration written for it was reverted rather than committed. A fix for a bug
that does not exist is a comment that asserts something false about the build, and it
would have made the next reader believe the module had a staleness problem it does not
have. The repo has spent this sweep removing no-op rules and unprobed assertions; adding
one here, in a commit whose subject is about honesty, would have been the same mistake at
a different altitude.

## Links

- `2026-10-04-test-execution-integrity` — the same principle one level up: a green
  pass/fail is not a measurement
- `2026-10-04-measurement-integrity` — why a gate that reports a boolean where it could
  report a number is the wrong shape
- `scripts/check-test-task-inputs.py`, `scripts/tests/test_check_test_task_inputs.py`
- `shared/build.gradle.kts` — the `maestro.root` property and the `Maestro` input
