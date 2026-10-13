# Verification — <title>

> Written by `speckit-engineering-verify-design`, **before any code exists**. Its purpose is to
> make it impossible to retro-fit evidence: the strategy is fixed now, and `verify` measures what
> actually happened against it.
>
> Nothing in this document may claim a test has run. Nothing has been implemented yet.

## Verification objectives

_<What "correct" means for this change, in terms of what will be observed. Two or three sentences.>

## Acceptance criteria coverage

| Acceptance criterion | Scenarios | Status |
|---|---|---|
| AC-01 | V-01, V-02 | planned |
| AC-02 | — | **GAP — <why, and when it will be covered>** |

_Every `AC-NN` appears here. An `AC-NN` with no scenario is a gap — either write it, or record it
in `## Known limitations`. Do not let one pass silently._

## Scenarios

### V-01 — <what breaks if this is wrong>

- **Acceptance:** AC-01
- **Transition:** `<Idle>` --`start()`--> `<Ticking>` (`model.md`)
- **Preconditions:** <state, fakes, clock>
- **Sequence:** <the events, in order>
- **Expected state:** <observable>
- **Expected side effects:** <observable>
- **Invariants checked:** INV-01
- **Test level:** unit | integration
- **Platform:** jvm | android | common
- **Deterministic reproduction:** `<exact command>`

### V-02 — failure injection — <what breaks if this is wrong>

- **Acceptance:** AC-02
- **Makes fail by:** <the fake that throws, the repository returning `Result.failure`, a clock that
  does not advance>

## Unit tests

_<Scenarios that need no collaborators._

- Virtual time via `runTest` + `advanceUntilIdle()`. **Never** `delay(N)` as synchronization.
- `MutableClock.kt`, `AwaitState.kt` from `shared/src/commonTest/.../test/helpers/`.
- Fakes from `shared/src/commonTest/.../test/fakes/FakeRepositories.kt`. MockK only for outgoing
  calls you assert on, never for state.
- Every new test class carries `@Tag("fast")` or `@Tag("slow")`, or `check-test-runs.py` fails.
  `slow` means it crosses a process boundary — not "slow to run".

## Integration tests

_<Scenarios needing real collaborators: Room, DataStore, WorkManager._

## UI / E2E tests

*(when applicable)*

## State-transition coverage

_<Which `model.md` transitions are covered by which scenario, and which are not._

| Transition | Scenario |
|---|---|
| `Idle --start()--> Ticking` | V-01 |

_Uncovered transitions are named explicitly. "All transitions are covered" is only true if you
counted them against the table._

## Concurrency and cancellation tests

_<Parallel calls, re-entrancy, cancellation mid-flight, scope lifetime.>

`DebugProbes.dumpCoroutines()` can assert no coroutine was left suspended. It is **JVM only** — it
needs the ByteBuddy agent and does not work on Android — so any scenario depending on it records
that limitation below.

## Failure injection and recovery

_<One entry per failure transition in `model.md`: how the effect is made to fail, and what the
recovery expectation is.>

## Regression tests

_<Existing tests that must stay green, and the command that runs them._

## Observability and diagnostics

_Only with a diagnostic purpose. Delete this section if the change is not diagnostic._

- **Data needed to detect the problem:** <what>
- **Where it is observed:** <log, metric, dump, test-visible trace>
- **Correlation:** <how events of one operation are tied together — an operation id, a generation>
- **Redaction:** <what must never be logged — tokens, credentials, user content>
- **How we will know it helped:** <the check that proves the diagnostic actually localized the
  problem. A diagnostic whose value is unproven is unproven cost.>

## Commands and expected outcomes

```
<command>            → <expected result>
<command>            → <expected result>
```

## Known limitations

_<What cannot be verified, and why. Also: flakiness risk, platform gaps, anything requiring real
time or a real device. An honest gap here is worth more than a scenario that fails in CI on
someone else's machine._