---
title: "MR-1 retro — three ADRs recorded a test constraint that had already been fixed"
date: 2026-09-28
tags: [retro, tech-debt, tests, coroutines]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Retro after MR-1 of the tech-debt roadmap (`mr-1-test-virtualization`).

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 1 (`FakeRepositories.kt`, +14/−3) |
| Test files changed | 11 (+126/−122) |
| Real `delay()` calls removed | 109 (73 slot/coordinator + 36 `DraftMviViewModelTest`) |
| `:shared:jvmTest` | 1134 tests, 0 failed, 0 skipped |
| detekt (shared) | 0 findings |

## CRITICAL — the premise of three ADRs was stale

The roadmap's MR-1 was scoped from three ADRs that agreed on a root cause:

> `FakeProfileAwareCurrentUser` defaults to `Dispatchers.Default` — a real thread pool
> in a test double, which `advanceUntilIdle()` cannot drive. — `2026-09-27-mr1-retro-findings` R1,
> repeated as R6 in `2026-09-28-mr2-retro-findings` and as ledger #3 in
> `2026-09-27-write-layer-soundness`.

The planned fix was to move that default to a test dispatcher. **The default was already
`Dispatchers.Unconfined`.** Commit `560f3bf8` ("single state-update entry + ViewModel
sweep") changed it, and its own ADR says so:

> `FakeProfileAwareCurrentUser` defaulted to `dispatcher = Dispatchers.Default` — a real
> thread pool in a test double, which `advanceUntilIdle()` cannot drive. Both overloads
> now default to `Dispatchers.Unconfined`. Note honestly: **this was not what fixed the
> failing tests**.

That ADR recorded the change *and* recorded that it was unverified. Three subsequent ADRs
then cited the pre-change state as current, and `SlotTestFixtures` documented it in a KDoc
directly above every slot test. MR-2 recorded three separate attempts to bind a scheduler
fake, all reverted, and concluded the fix was "upstream of the fakes".

**None of this needed a new dispatcher.** `Unconfined` drains inline, so the
`scopedUserId` emission lands before a slot subscribes.

### Verified, not assumed

A probe test built the fake on `StandardTestDispatcher(testScheduler)` and printed:

```
PROBE scopedUserId=UserId(value=test-user) tasks=[t1, t2]
```

`scopedUserId` emits and `observeAll()` delivers through `advanceUntilIdle()` with no
wall-clock time. All 45 slot tests then passed with `delay(SETTLE)` → `runCurrent()`, and
the 300 ms debounce crossed with `advanceTimeBy`. The `SETTLE = 100L` constant and its
KDoc are deleted.

**Had the roadmap been followed literally, it would have re-introduced the exact bug
`560f3bf8` fixed** — moving the default back to `Dispatchers.Default` to satisfy a stale
sentence in a retro.

## Bugs fixed

### A `SupervisorJob` leaked on every read of `FakeTaskRepository.currentUser`

```kotlin
// before — constructs a fake, and so a CoroutineScope(SupervisorJob()), per access
private val currentUser: ProfileAwareCurrentUser
    get() = explicitCurrentUser ?: FakeProfileAwareCurrentUser()
```

`FakeProfileAwareCurrentUser` owns a `CoroutineScope(SupervisorJob() + dispatcher)` that
nothing ever cancels. The repository reads `currentUser` from 17 call sites, so each read
left a supervisor job alive for the test's duration. Now `by lazy`, matching the six
sibling fakes that already used a constructor `val`.

This is the concrete instance of the general rule: **a `get()` that constructs a
scope-owning object is a leak**, and a constructor default is evaluated once by
construction.

## LOW — filed, not fixed here

- Three test files keep real `delay()` on purpose, each with the reason already written in
  the file: `SyncRepositoryCoalescingTest` (freezes a coroutine to prove the coalescing
  guard), `WriteToolsTest` (waits for a timestamp to change), `AgendaViewModelTest` (the
  infinite `todayFlow` means the scheduler can never drain).
- `NoRealDelayInTest` is active and registered, but its `value <= 500` cutoff meant it
  could not have flagged **any** of the 109 sites — they were `delay(100)`, `delay(20)`,
  `delay(400)`. The threshold is the bug: a 100 ms settle is exactly the case the rule
  exists to catch, and it was silently exempt. Drop it to `value <= 0`, or report every
  `delay` in a test file and let the severity carry the judgement.
- `TaskDetailViewModelTest > TitleChanged debounce saves after delay` was deleted with the
  god-VM in MR-2, so ledger #3's headline symptom no longer exists. Its stated cause was the
  same stale premise, and the equivalent debounce case in `TaskDraftSlotTest` passes on
  virtual time — but the test file itself is gone, so this is **closed as obsolete**, not
  as fixed. Nothing was verified against the original assertion.

## Rules

- **Verify a claim about current behaviour before planning a fix around it.** A retro ADR
  describes the tree at the time it was written; four days of commits can invalidate it.
  `git log -S` on the symbol settles it in one command.
- **When an ADR says a change "was not what fixed it", the next reader inherits the
  ambiguity.** Either verify the change and record the evidence, or say the constraint
  still holds. Leaving both true is how three ADRs came to disagree with the code.
- **Prefer "run the experiment" over "reason about the dispatcher."** The three reverted
  attempts in MR-2 cost more than the probe test that answered the question in one run.

## Links

- `2026-09-27-mr1-retro-findings` — R1, superseded
- `2026-09-28-mr2-retro-findings` — R6, superseded
- `2026-09-27-write-layer-soundness` — ledger #3, superseded
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — commit `560f3bf8`, the real change
- `2026-09-27-feature-slot-pattern` — the suite this unblocks
