---
name: singularity-todo-unwired-surface-audit
description: Find code that is fully implemented but wired to nothing — a screen no graph composes, a callback whose empty default defeats its own fallback, a DAO no Koin module binds, a UI affordance with no control. Use when a feature "looks done" but does nothing, when a ViewModel or Composable seems to have no effect, when reviewing a large feature for gaps, or when writing a flow that turns out to have nothing to drive. Run scripts/find-unwired-surfaces.py first — it is cheap and catches the static shapes.
---

# Unwired Surfaces

The recurring defect in this codebase is not a broken feature. It is an
**unreachable** one: code that compiles, has a ViewModel, has tests, and is
invoked by nothing. Nothing fails, because everything that is wired works.

## Why no test catches it

A test exercises the code that *is* wired. Every instance below was found by
driving the real UI or by grepping for call sites — never by a failing unit
test:

| Surface | What existed | What was missing |
|---|---|---|
| Agenda context menu | `buildTaskContextMenu` (28 items), `AgendaViewModel.ShowTaskContextMenu`, `AgendaNavigator.showTaskContextMenu` | The slot was named `desktopContextMenuHost`; only the JVM graph passed a renderer, Android took the no-op default. |
| Task editor attribute rows | `DatePickerSheet`, `TimePickerSheet`, `TaskEditorSheetsHost` | `onClick` defaulted to `{}` instead of `null`, defeating the row's own `onClick ?: openOwnSheet` fallback. |
| Koin bindings | 3 Room DAOs with full repositories behind them | No `get<AppDatabase>().…()` binding; resolving the repository threw at runtime. |
| `SyncConfigScreen` | Screen + a complete `SyncViewModel` (auto-sync, interval, sync-now, test-connection) | Composed by nothing, and absent from `SettingsTab` — unreachable by any path. |
| Notes row actions | `NotesListViewModel` implements pin, archive, delete, multi-select, sort | No control dispatches them; long-press does nothing. |
| Calendar header | `GoToday`, `GoNext`, `GoPrevious`, `ToggleMiniCalendar` intents | No control for them; only the mode switcher is wired. |
| Pomodoro start | `PomodoroAlarmScheduler` calling `setAlarmClock` | Exact-alarm permission undeclared and the `SecurityException` unguarded — killed the process. |

The common shape: **the wiring is the part that rots, and it rots silently.**

## The static pass

```bash
scripts/find-unwired-surfaces.py          # report; exit 1 when anything is found
scripts/find-unwired-surfaces.py --quiet  # findings only
```

Detects three shapes, each validated against a synthetic fixture so a detector
that never fires cannot pass unnoticed:

- **screen** — a public `*Screen` composable with no call site anywhere.
- **default-noop** — a callback declared `= {}` that a `?:` consumer reads as
  "supplied", so the fallback never runs.
- **di-binding** — a Room DAO accessor on `AppDatabase` with no Koin binding.

The one thing that makes the screen check work: **KDoc mentions are not calls.**
`[SyncConfigScreen]` in a doc comment is a text match for the symbol, so a naive
grep reports the screen as wired. The script strips comments before counting.

Two regex details worth keeping if you extend it: a parameter declared inside a
`data class Foo(val …, val onClick: … = {})` list follows a comma rather than
starting a line, and a function type appears both as `() -> Unit` and as
`(() -> Unit)`. Accepting only one of either form halves the coverage silently.

Current output on `main` is one finding — `SyncConfigScreen` — which is the
known, recorded gap. Anything new is worth a look.

## The shapes a static pass cannot see

Reach for these when the script is clean but a feature still does nothing.

- **A platform slot that only one platform fills.** A parameter whose name
  names a platform (`desktopContextMenuHost`) reads as a statement about where
  the feature lives, and that is usually believed. Prefer a neutral name and
  let each platform's graph pass its own renderer.
- **A UI affordance with no control.** Intents in a ViewModel prove intent
  exists, not that anything dispatches it. Confirm on the device: the element
  has no `resource-id` and tapping it changes nothing. Drive the real screen —
  see the probe technique in `singularity-todo-maestro-flows`.
- **A guard that cannot fail.** A `try`/`?:`/default that is unreachable because
  the permission or precondition it checks is never actually consulted. Read the
  manifest against what the code calls.

## What to do with a finding

The fix depends on whether the feature is meant to ship:

1. **Meant to work and does not** — fix it in the same change. Every row in the
   table above was a small fix once found; the cost of leaving them is a user
   pressing a button that does nothing.
2. **Deliberately deferred** — record it as an ADR with what is missing and what
   would close it, and name it in the relevant skill's coverage-gaps section. A
   gap nobody can find is a gap that gets rediscovered.
3. **Actually not wanted** — delete the code. A screen with a ViewModel behind it
   reads as a feature to the next reader whether or not it is reachable.

Wiring the control is **product** work, not test work: deciding where a sync
config screen belongs is a navigation-model decision, not a flow. Keep it out of
a test PR, but do not leave it unrecorded either.

## When to run it

- After a feature lands that had a platform split or a conditional callback.
- Before writing a flow for something whose behaviour you are unsure of — it
  may have nothing to drive.
- During review of a large feature, and periodically: the pattern is invisible
  to compilation and to unit tests, so nothing surfaces it on its own.

## Related

- `scripts/find-unwired-surfaces.py` — the static pass
- `singularity-todo-maestro-flows` — the probe technique for affordances a
  static pass cannot see
- `singularity-todo-di-graph-testing` — the DAO binding checklist
- `singularity-todo-shared-ui-components` — callback defaults and content slots
- Evidence: `2026-09-29-sync-config-screen-has-no-host`,
  `2026-09-29-editor-row-onclick-noop-default`,
  `2026-09-29-missing-koin-dao-bindings`,
  `2026-09-29-task-longpress-menu-and-archive-restore`,
  `2026-09-29-notes-and-calendar-unreachable-controls`,
  `2026-09-29-pomodoro-exact-alarm-crash`
