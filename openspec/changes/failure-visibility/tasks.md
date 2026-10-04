# Tasks — failure-visibility

> **Partially done, and NOT archived.** A change is archived when its requirements describe
> reality; this one does not, so archiving it would put a spec in `openspec/specs/` describing
> behaviour that was never built — the same class of error the 2026-10-04 retro found in nine
> backlog entries. The audit on 2026-10-05:
>
> | REQ | State |
> |---|---|
> | REQ-1 `emit` is total | **done** — `48becc79`. One task below is not: the test that proves "does not report" with a *real* reporter rather than a no-op. |
> | REQ-2 a user-initiated failure reaches the user | **not started.** The agenda toggle work (#132). Nothing in this session touched it. |
> | REQ-3 the bypass is recorded | **half done** — the breadcrumb landed in `a068b432`; the test that asserts a throwing read produces *both* report and breadcrumb did not. |
> | REQ-4 (in `crash-reporting`) | **done** — the reporter is real; see `openspec/specs/crash-reporting/`. |
>
> The two unfinished halves are filed rather than left here, because a checklist nobody works from
> is the backlog-rot this repository already has a decision record about.

## REQ-1 — a dropped event, not a raised one

- [x] Make `EventBus.emit` total. Caught the closed-channel case directly rather than routing
      through `tryEmit`; `tryEmit` cannot suspend, so it would have traded a throw for a dropped
      event under backpressure. Backpressure is preserved — `send` still suspends on a full buffer.
- [x] Remove the resulting nullable from `Celebration`'s call sites and the two
      `emit`-after-clear paths in `AgendaViewModel`.
- [x] Test: an emit after `onCleared()` does not raise, does not cancel the emitting coroutine,
      and does not cancel the bus. `EventBusAfterCloseTest`, 5 tests.
- [x] Confirm no caller relied on the throw as control flow. Grep for `catch` around `emit`.
- [ ] **The gap, filed:** the "does not report" half of the test — asserting with a **real**
      recording reporter rather than a no-op. The task text called this out as the part that
      regresses silently, and it is right: the current tests prove the drop is counted, not that
      it stays out of the report channel. Filed alongside #132.

## REQ-2 — not started

Everything in this section is undone. The agenda mutations still discard their `Result`, which is
#132, and it is the one item here that produces a *user-visible* silence rather than a diagnostic
one.

## REQ-2 — a user-initiated failure reaches the user

- [ ] Add an error variant to `AgendaUiEvent`, and render it. Check how the agenda screen already
      surfaces the undo snackbar — reusing that channel is probably cheaper than a second one.
- [ ] Route `toggleComplete`, `togglePinned` and `restore` through the funnel so the failure both
      surfaces and reports. Reporting is already in place; do not duplicate it.
- [ ] Audit the rest of `AgendaViewModel` for the same shape: the three `emit(...)` mutations can
      also throw on a closed channel and now report as background failures.
- [ ] Test: a failing toggle sets the error state and reports once under a stable key.

## REQ-3 — the bypass is recorded

- [ ] Breadcrumb the version-gate fail-open path, using the same key the failure is reported
      under, so the two are greppable in the same report.
- [ ] Test: a throwing `snapshot()` produces both the report and the breadcrumb. A breadcrumb
      asserted only by reading the code is not a breadcrumb.

## Cross-checks before merging

- [ ] `MviViewModelCrashReportingTest` still passes: making `emit` total must not have moved a
      failure from the report channel to the dropped channel unnoticed.
- [ ] The widened `CrashReportingWiringTest` still passes — `catchTo` additions in the agenda
      screens are a new call surface for it to check.
- [ ] Update `docs/decisions/DIGEST.md` via `./scripts/refresh-decisions-digest.sh` if an ADR is
      written for the emit decision.

## At archive time, not before

- [ ] Move the module's row in `openspec/specs/MODULE-INDEX.md` from **Not covered**
      to **Covered**. Not now: the spec does not exist in `openspec/specs/` until the
      change is archived, and the index is supposed to stay honest about what is not
      yet covered.
