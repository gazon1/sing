# desktop-nav-goBack-blank-screen

**Status:** proposed · **Issue:** #27 · **Backlog:** `docs/decisions/deferred-backlog-archive.md#desktop-nav-goBack-blank-screen`

## What

Fix the desktop defect where saving from the saved-agenda or task-create screen and
then going back leaves the entire app UI blank.

## Why

This is a gate-level blocker, not a cosmetic bug. After the back press the
`SemanticsTree` reports **0 nodes** and every tag lookup fails, which means every
desktop flow test that ends in "Save → Back" is blocked:

- `SavedAgendaCreateFlowTest` (C-01…C-03)
- `SavedAgendaEditFlowTest` (D-02, E-09)
- `CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`

MR-0 cannot proceed to the desktop matrix while this is open.

## Already ruled out

- **Not a clock or `FakeAppDatabase` issue** — the task *is* persisted; it is
  visible in the DB snapshot.
- **Not a `SavedAgendaViewModel` init failure** — the `Results` state is reached,
  confirmed by log.
- **Not a `goBack()` call-site problem** — the navigation itself completes; kermit
  shows a clean `onEnd` path ("Scheduled sync stopped").

So the write succeeds, the ViewModel reaches its loaded state, and the back
navigation completes — and the tree is still empty. That combination points at the
**shell layer**, not at any of the screens involved.

## The diagnostic that was already tried and does not work

#45 records a proposed red-border debug overlay on `NavDisplay`. It is **not
implementable as described**: at the moment of failure the compose tree is empty,
so a modifier on `NavDisplay` has nothing to render. Do not re-attempt it.

The diagnostic has to live in the shell layer instead —
`DesktopShellNav3Root` / `DesktopShellNav3` — where a `LaunchedEffect` or
`remember` on `currentRoute` can observe state while the tree is still empty.

## Open question worth settling first

An empty tree after a *completed* back navigation is the signature of a back stack
that reports a destination the shell cannot render — for example a route restored
from saved state whose entry provider no longer exists, or a `taskId` that the
restored entry resolves to nothing. Since the shell persists its back stack
(`singularity-todo-nav3-savedstate`), a stale persisted entry is a live candidate
and would explain why the flow works in a fresh session and fails after one.

That is a hypothesis, not a finding. Confirm it with the shell-layer diagnostic
before changing any behaviour.

## Out of scope

- The Android navigation path. This is desktop-only; Maestro covers Android.
- Any change to the saved-state format. If a stale entry turns out to be the cause,
  the fix is to tolerate or migrate it, not to redefine it.

## How

1. Add the shell-layer diagnostic to observe `currentRoute` and back-stack contents
   when the tree empties.
2. Capture it on a failing run before changing anything.
3. Fix the cause the capture identifies, not the symptom.
4. Re-run the three blocked flow suites; they are the regression net.

## OpenSpec artifacts

- `specs/desktop-navigation/spec.md` — back navigation must always yield a
  renderable tree
