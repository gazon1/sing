# Tasks — failure-visibility

## REQ-1 — a dropped event, not a raised one

- [ ] Make `EventBus.emit` total. It is `Channel.send`; decide explicitly between routing
      through the existing `tryEmit` and catching the closed-channel case, and record which.
- [ ] Remove the resulting nullable from `Celebration`'s call sites and the two
      `emit`-after-clear paths in `AgendaViewModel`.
- [ ] Test: an emit after `onCleared()` does not raise, does not report, and does not cancel the
      emitting coroutine. Test with the crash reporter installed, not a no-op — "does not report"
      is the part that regresses silently.
- [ ] Confirm no caller relied on the throw as control flow. Grep for `catch` around `emit`.

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
