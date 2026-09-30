---
title: "runTest background work: advanceUntilIdle does not pump an idle foreground"
date: 2026-09-30
status: accepted
tags: [testing, vm]
---

## Context

MR-5's `TagRenameTest` went through three harness revisions because the
ViewModel kept reading `Loading` on every assertion. The investigation ended
in a scheduling rule about `kotlinx.coroutines.test` that is not written down
anywhere in this repo, and that silently produces **vacuously passing tests** —
the worst failure mode the project's testing discipline knows.

The empirical contract, pinned by `TestScopeSemanticsTest`:

| Situation | `advanceUntilIdle()` | `runCurrent()` |
|---|---|---|
| Only `backgroundScope` work queued | **does not run it** | runs it |
| Foreground work also pending | runs both | runs both |
| Only foreground work queued | runs it | runs it |

`advanceUntilIdle()` drains the queue only while **foreground** work is
pending; `backgroundScope` tasks execute as a side effect of that pump. With an
idle foreground it returns immediately and background work stays queued
forever. (`DraftMviViewModelTest` — a backgroundScope-based VM test that works
— drives everything with `runCurrent()`, which is why the trap never bit there.)

The dangerous part is not the `Loading` — it is what the `Loading` does to
**rejection tests**. `rename rejects a blank name and leaves the tag untouched`
asserts the stored name is unchanged. A rename that silently did *nothing*
satisfies that. Five rejection tests were green against a ViewModel that never
executed a single statement of the feature under test. The suite was lying, and
every signal said the code was fine: it compiled, detekt was clean, and 9 of 14
tests passed.

## Idea

Three ways a ViewModel test can host a forever-collecting VM scope:

1. **Foreground scope under a child Job** —
   `AutoCloseableCoroutineScope(testScope.coroutineContext + Job(parent))`,
   cancelled at test end. VM work *is* foreground work, so `advanceUntilIdle()`
   always drives it.
2. **`backgroundScope` + suspension** — keep the VM on `backgroundScope` and
   drive the test with `runCurrent()` or a helper that *suspends* until the
   state matches; while the body is suspended, `runTest` pumps background work.
3. **Foreground scope without a child Job** —
   `AutoCloseableCoroutineScope(testScope.coroutineContext)`: works until
   `close()`, which cancels the TestScope itself and fails `runTest` with
   `JobCancellationException`.

## Decision

Shape (1) is the convention for state-asserting VM tests, matching
`MviViewModelTest.VmUnderTest`. `TagRenameTest` uses it; the harness is ten
lines (`TagsHarness` + `newHarness`). Shape (2) remains valid where virtual
time and debouncing matter (`DraftMviViewModelTest`).

`TestScopeSemanticsTest` pins the table above. **If it fails after a
kotlinx-coroutines upgrade, the scheduling contract changed** — re-audit every
ViewModel test that touches `backgroundScope` before trusting green suites.

## Rationale

The debugging detour itself is the rationale for writing this down. The
`Loading` looked like a repository bug, then a user-scoping bug, then a
`FakeProfileAwareCurrentUser` bug; two of the three hypotheses were "fixed"
blind and the suite stayed equally green. A probe test with a bare
`backgroundScope.launch` even showed the work *running* — because that probe
had foreground work pending, which carried the background task along. Only an
isolated probe (background work alone, then background + foreground) exposed
the pump rule.

The cost of the trap is quadratic: the harness bug hides itself, and the
hidden bug then hides *real* bugs behind vacuously green rejection tests. The
defence is structural — every rejection test must be paired with a success
test on the same code path, so "did nothing" cannot look like "refused
correctly". `TagRenameTest` now follows that pairing explicitly.

## Consequences

- **Always** host a state-asserting ViewModel on the foreground `TestScope`
  context (child Job + explicit cancel), or drive a `backgroundScope` VM with
  `runCurrent()` / suspending assertions — never plain `advanceUntilIdle()`.
- **Never** trust a rejection test that has no passing sibling on the same
  write path; "still equals the original" is also what a no-op produces.
- The semantics table lives in one test, so a coroutines upgrade that breaks
  the rule fails loudly there instead of silently changing every VM test's
  meaning. (`TestScope[test started]` vs `BackgroundWork` context elements are
  the implementation detail; build nothing on them.)

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/test/TestScopeSemanticsTest.kt` — the pinning test
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tags/TagRenameTest.kt` — harness shape (1)
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/DraftMviViewModelTest.kt` — harness shape (2)
- `2026-09-30-mvi-error-path-contract.md` — the second bug this investigation surfaced
