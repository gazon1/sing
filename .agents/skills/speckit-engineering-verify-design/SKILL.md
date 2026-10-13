---
name: speckit-engineering-verify-design
description: 'Design how correctness will be proven BEFORE any code exists: the test matrix, which AC-NN and which transition each scenario covers, counterexamples, failure injection and observability. Fourth step of Engineering SDD; never claims a test has passed.'
disable-model-invocation: true
---

# Engineering SDD — Verify-Design

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug`, `depth` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `openspec/changes/<slug>/spec.md`, `model.md`, `investigation.md`, and existing tests.
- Write: `openspec/changes/<slug>/verification.md`

## Goal

Decide **now, before the code exists**, how correctness will be proven. The point of this step is
that evidence is designed first and collected later — never the reverse.

### Required sections

```
# <title>

## Verification objectives
## Acceptance criteria coverage
## Unit tests
## Integration tests
## UI / E2E tests                     (when applicable)
## State-transition coverage          (when applicable)
## Concurrency and cancellation tests (when applicable)
## Failure injection and recovery
## Regression tests
## Observability and diagnostics
## Commands and expected outcomes
## Known limitations
```

Keep every section; mark the ones marked *when applicable* as *Not applicable* with a reason.

### Scenario format

Every scenario carries enough to be executed by someone who has no memory of writing it:

```markdown
### V-01 — <what breaks if this is wrong>
- **Acceptance:** AC-03
- **Transition:** `Running` --`cancel()`--> `Cancelled` (model.md)
- **Preconditions:** <state, fakes, clock>
- **Sequence:** <the events, in order>
- **Expected state:** <observable>
- **Expected side effects:** <observable>
- **Invariants checked:** INV-02
- **Test level:** unit | integration
- **Platform:** jvm | android | common
- **Deterministic reproduction:** <the exact command>
```

`## Acceptance criteria coverage` must be a table mapping **every** `AC-NN` to the scenarios that
cover it. An `AC-NN` with no scenario is a gap — either write the scenario or say in
`## Known limitations` why it cannot be verified yet.

### Determinism is a requirement, not a preference

The test suite must not need a real clock, real network or a real sleep to be trustworthy.

- Virtual time: `runTest` + `advanceUntilIdle()`. **Never** `delay(N)` as synchronization.
- `MutableClock.kt`, `AwaitState.kt` from `shared/src/commonTest/.../test/helpers/`.
- Fakes from `shared/src/commonTest/.../test/fakes/FakeRepositories.kt`. MockK only for outgoing
  calls you need to assert on, never for state.
- Every test class carries `@Tag("fast")` or `@Tag("slow")`. `slow` means it crosses a process
  boundary — Compose harness, real file/DB/clock, Konsist scan, subprocess — not "slow to run".
- No `@Disabled` and no assumption guards. They make `check-test-runs.py` fail.

When cancellation or scope lifetime is under test, `DebugProbes.dumpCoroutines()` can assert no
coroutine was left suspended. Note it is **JVM only** — it needs the ByteBuddy agent and does not
work on Android — so a cancellation scenario needing it must say so in `## Known limitations`.

### Failure injection

For each `model.md` failure transition, name the test that makes the effect fail: a fake that
throws, a repository returning `Result.failure`, a clock that does not advance. "We should probably
test the error path" is not a plan.

### Observability — only with a diagnostic purpose

Do not propose logging for its own sake. For a diagnostic change, specify:

- what data is needed to detect the problem,
- where it is observed,
- how events belonging to one operation are correlated (an operation id, a generation counter),
- what must be redacted — never log tokens, credentials or user content,
- **how you will check the diagnostic actually helped.** A diagnostic whose value is unproven is
  unproven cost.

## Forbidden

- Claiming or implying that any test has run. Nothing has been implemented yet. A sentence like
  "the test passes" here is a fabricated result.
- Writing the test bodies. Design the scenarios; `implement` writes them.
- Accepting a criterion with no scenario without recording it under `## Known limitations`.
- Proposing a timing-sensitive test where a deterministic one exists. A flaky test costs more than a
  missing one, because it trains everyone to ignore the suite.
- Editing `spec.md`, `model.md`, `design.md` or any code.

## Done When

- `verification.md` has every required section.
- Every `AC-NN` maps to at least one scenario, or is listed as an explicit gap.
- Every significant `model.md` transition is covered by a named scenario.
- Every scenario names a deterministic reproduction command.
- Nothing anywhere claims a test has been executed or has passed.