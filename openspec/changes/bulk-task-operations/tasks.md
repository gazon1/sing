# Tasks — bulk-task-operations

**Status:** proposed

## Decision

- [ ] **Where does bulk live** — task list, agenda, or both. The list is the
      obvious home; the agenda is where bulk is most useful in practice and has a
      different selection story. Decide before building.

## Implementation

- [ ] **Selection state** — follow the existing `singularity-todo-multi-select`
      pattern rather than inventing a second one.
- [ ] **Gesture arbitration** — long-press starts a selection; it must not also
      open the context menu or fire a swipe action. Two gesture systems already
      share this screen.
- [ ] **Action bar** — complete and delete route through the existing
      `TaskMutationsUseCase`. No new repository method.
- [ ] **Bulk archive** — obvious once selection exists, but its semantics
      (reminders, logbook entries) are a separate decision. Deliberately excluded
      from this change.

## Verification

- [ ] Partial failure leaves **nothing** changed — the guarantee the use case
      exists to provide, and the one no current test can reach through the UI
- [ ] Selection is visibly counted before the user acts
- [ ] Completing the operation clears the selection and reports the count
- [ ] Long-press selects without opening the detail screen
- [ ] Desktop UI test for each of the above

## Explicitly not doing

- Server-side bulk operations; the sync engine queues individual writes and
  batching them raises its own conflict-resolution question
- Any new repository or use-case method
