# Design — the refresh is one run

The rationale for this change lives in
`docs/decisions/2026-10-25-the-refresh-is-one-run.md`. This file records the shape
of the implementation and what would have to be undone.

## The shape

- `core/refresh/RefreshOutcome.kt` — what a refresh produced: how many tasks moved
  into the archive, and what the synchronisation did. The synchronisation's own
  verdict travels in it unchanged, so there is one vocabulary for a
  synchronisation outcome and not two.
- `core/refresh/RefreshUiState.kt` — whether a refresh is running. The result is
  not state: it is an event that is delivered once.
- `core/refresh/RefreshUiEvent.kt` — the one-shot event carrying the result, and
  the mapping from a result to the message the user reads. The mapping lives here
  so that every screen says the same thing about the same outcome.
- `core/refresh/CompletedTaskArchiver.kt` — the narrow contract the core needs
  from the archive step.
- `core/refresh/RefreshCoordinator.kt` — the owner of the run: a conflated
  channel, a counter of completed runs, and a request that returns the result.
  The run executes in the owner's own scope, and the archive step runs before
  the synchronisation step.
- `core/refresh/RefreshViewModel.kt` — the shared view model. It is raised by the
  container on each screen, so it lives by the rules of the navigation's
  view-model store and the report survives the user leaving the screen.
- `core/refresh/RefreshableContent.kt` — the shared container: the gesture, the
  indicator, and the report.
- `core/di/RefreshDiModule.kt` — the bindings, in the same shape as the other
  per-domain modules, aggregated by `core/di/Modules.kt`.

## Why the container sits above the scrollable content

The gesture is driven by the scrollable content: what cannot scroll further
reports the leftover movement upwards, and that is what the gesture reads. The
container therefore wraps the screen's content rather than a list inside it,
and the screen keeps whatever scrolling it already had.

Two screens have no vertical scroll of their own — the task detail and the
pomodoro — and for them the container supplies one. On the calendar the only
vertical scrollable is the hour grid, which is scrolled to the current hour, so
the gesture is reachable only at the top of the grid. That is a limitation of
the screen, not of the container, and it is recorded rather than worked around.

## Why the archive step has its own contract

The core takes the archive step through a port it declares itself, and the
archive feature's port inherits it. The alternative — the core taking the
feature's port — puts a dependency from the core onto a feature, which is the
direction the rest of the project does not have. The implementation of the
archive step does not change by a line: the contract it already satisfies is
the one the core asks for.

## Why the screens keep their own view models

A refresh is not a fact about a task list. Giving eighteen view models a state
field, an intent and a handler for a subsystem they do not own would put the
same code in eighteen places, and eighteen places are eighteen chances to
disagree about when a refresh is running. The container raises one shared view
model instead, and the screens change by wrapping their content.

The one thing this costs is that the report is not part of a screen's own
state: it is an event, delivered once, to the screen that asked.

## Rollback risk

**Low.** The change adds one package, one module of bindings and one narrow
port, and edits eighteen screens to wrap their content. Undoing it means
deleting the package, removing the module from the aggregator, dropping the
inherited contract from the archive feature's port, and removing the wrapper
from the eighteen screens.

Two things are not trivially undone.

The archive step's placement is a behavioural fact once it has run: tasks that
were moved into the archive are in the archive, and the server has been told.
Undoing the change does not bring them back — and it should not, because they
were completed.

The gesture on the calendar and on the two screens that gained a vertical
scroll changes what the user can do with those screens. Undoing the change
removes the gesture and the scroll it needed, and the screens go back to what
they were.
