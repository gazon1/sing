# bulk-task-operations

**Status:** proposed · **Issue:** #36 · **Backlog:** `docs/decisions/deferred-backlog.md#bulk-task-operations-have-no-ui`

## What

Give the task list a multi-select mode, so the atomic bulk operations that already
exist are reachable from the app.

## Why

`TaskMutationsUseCase.bulkComplete(ids)` and `bulkDelete(ids)` are implemented,
unit tested, and Koin-bound. No ViewModel injects the use case, and the task list
has no multi-select — so the atomicity guarantee those two methods exist to provide
is never exercised in the running app.

This is not dead code. `2026-09-05-refactoring-summary` created the use case by
collapsing five pass-through use cases, and `2026-09-07-dogfooding-followups`
stripped it back while keeping exactly these two methods *because* they enforce a
guarantee the per-item path does not. Deleting it would discard a deliberate
decision; leaving it unwired leaves the guarantee theoretical.

The repository already has a multi-select pattern for Compose Multiplatform list
screens (see the `singularity-todo-multi-select` skill), so the UI work is
following an existing convention rather than inventing one.

## Decision required

**Does bulk belong in the task list, the agenda, or both?** The list screen is the
obvious home. The agenda is where bulk is most useful in practice, and it has a
different selection story (date sections). Worth deciding before building rather
than after.

Note the interaction with swipe-to-dismiss: a long-press that starts a selection
must not also fire a swipe action, and the two gesture systems already share a
screen.

## Out of scope

- Any new repository method. `bulkComplete` / `bulkDelete` already exist and are
  the point.
- Server-side bulk operations. The sync engine queues individual writes today;
  batching them is a separate change with its own conflict-resolution question.
- Archiving in bulk, despite the obvious symmetry. Adding it is trivial once the
  selection exists; deciding the semantics (what happens to reminders, logbook
  entries) is not.

## How

1. Add selection state to the list ViewModel, following the existing multi-select
   pattern rather than a new one.
2. Route the action bar's complete/delete through the existing use case.
3. Verify atomicity is observable: a partial failure must not leave half the
   selection done. That is the guarantee worth testing, and it is the one no
   current test can reach through the UI.

## OpenSpec artifacts

- `specs/bulk-task-operations/spec.md` — selection and atomicity behaviour
