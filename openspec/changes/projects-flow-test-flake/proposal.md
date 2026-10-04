# projects-flow-test-flake

**Status:** proposed · **Issue:** #40 · **Backlog:** `docs/decisions/deferred-backlog.md#projects-flow-one-time-flake`

## What

Track down the one-in-five `NullPointerException` in `ProjectsFlowTest`, caused by
project draft state being read before its init collector seeded it.

## Why

One failure in five runs, never reproduced. The observed symptom is
`ProjectDetailViewModel.getDraftState()` returning `null` when read — draft state
read before the init collector seeded it.

Left alone, this is the most expensive kind of test failure to diagnose later: it
does not reproduce, it does not correlate with a change set, and by the time it
recurs the person reading it has no memory of the original run.

## Already ruled out

- The change sets at both the original observation and the rerun were tag-rename
  only — nothing touched projects or drafts.
- Not reproducible across three `--rerun-tasks` runs with the change set, one full
  rerun at MR-4, and a final `check.sh`.
- Suspected interaction with `shared:jvmTest` sharing the Gradle daemon. This is a
  **suspicion, not a measurement** — it was never tested by running the test class
  in isolation against a warm daemon.

## What makes this tractable

The flake has a stated shape: a property read from `init` before the collector that
seeds it has emitted. This project already has a documented rule for it —

> a property read from `init` **must** be declared before it; Kotlin initialises
> properties in declaration order, and an `init` block sees a later property as
  > `null`

— and a companion regression test, `TaskDetailCoordinatorGraphTest`, which pins
exactly this class of bug for the task detail graph. The projects graph has no
equivalent.

So the likely fix is not a timing patch. It is: confirm the read happens in `init`
before declaration, and add the missing companion guard.

## Out of scope

- Rewriting the test. The test is correct; it caught a real ordering hazard.
- Speeding up the suite. Not the issue.
- Any change to the shared daemon configuration. The daemon-sharing theory is
  untested and, if the real cause is declaration order, irrelevant.

## How

1. Reproduce deliberately rather than waiting: run the class repeatedly against a
   warm daemon, and once against a cold one, to test the daemon theory directly.
2. If it does not reproduce, read the ViewModel for the ordering shape *first* —
   the flake's own description already names the suspect.
3. Add a companion graph test for the projects ViewModel, mirroring
   `TaskDetailCoordinatorGraphTest`. Even without a reproduction this converts a
   silent hazard into a checked one.
4. Only if the ordering theory is wrong, capture per-test timing with `--scan`
   before touching anything else.

Step 4 is last deliberately: the cheaper explanation is already written down, and
timing analysis is expensive to do speculatively.

## OpenSpec artifacts

None. This is a defect fix, not a behaviour change — per `openspec/config.yaml`
`skip_specs`, a bug fix with no behaviour change is an ordinary PR. The spec below
captures the invariant that was violated, so the regression is described somewhere
even though no behaviour is being added.
