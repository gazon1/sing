# Tasks — blocked-completion-guard

## Phase 0 — decisions before code

- [x] **shared/**: record the decision as an ADR, and mark the earlier
      dependencies decision as superseded in the part that said completion is
      always allowed.
      Test: the repository's ADR status gate passes.
- [x] **shared/**: narrow the dependencies a task carries to the ones that
      block it, and correct the dead badge rule that read "has any dependency".
      Test: a repository test asserting an edge of a non-blocking kind is not
      in the task's dependency set, and a unit test asserting the badge rule
      agrees with the blocking predicate.

## Phase 1 — the blocking predicate

- [x] **shared/**: one pure function that answers which prerequisites are still
      open for a task, and its batch form.
      Test: a domain test per case — an open blocking prerequisite is a
      blocker; a non-blocking kind is not; a finished prerequisite is not; an
      archived prerequisite is not; a prerequisite that no longer exists is not;
      and the batch form agrees with the per-task form.

## Phase 2 — the confirmation on the agenda

- [x] **shared/**: the agenda does not close a blocked task; it holds the
      pending confirmation in its state instead.
      Test: a view-model test asserting the close does not reach the repository
      and that the pending confirmation is in the state.
- [x] **shared/**: confirming the pending confirmation closes the task;
      dismissing it does not.
      Test: two view-model tests — one asserting the close reaches the
      repository after the confirmation, one asserting it does not after the
      dismissal.
- [x] **shared/**: a task with no open prerequisite closes immediately, with no
      confirmation.
      Test: a view-model test asserting the close reaches the repository and
      no confirmation is raised.
- [x] **shared/** + **desktopApp/**: the shared confirmation dialog, naming the
      open prerequisites.
      Test: a desktop Compose test asserting the dialog shows the names and
      offers both answers, and a unit test over the wording.

## Phase 3 — the confirmation on the task detail

- [x] **shared/**: the task detail's own checkbox does not close a blocked
      task; it holds the pending confirmation in its state instead.
      Test: a slot test asserting the write does not happen and the pending
      confirmation is raised.
- [x] **shared/**: confirming the pending confirmation closes the task the way
      the surface always closed it — including for a recurring task, which is
      completed and rescheduled rather than simply stamped.
      Test: a slot test per case — the confirmation reaches the same write the
      unchecked path would have reached, and the recurring case reaches the
      rescheduling write.
- [x] **shared/**: taking a completed task back into work is never gated.
      Test: a slot test asserting the write happens with no confirmation.
- [x] **shared/**: the subtask checkbox is gated the same way.
      Test: a slot test asserting the write does not happen for a blocked
      subtask and does after the confirmation.

## Phase 4 — closing a project

- [x] **shared/**: the project entity gains its closed timestamp, and the
      database can be migrated to it.
      Test: a migration test asserting an existing project reads as not closed
      after the migration, and that a project can be closed afterwards.
- [x] **shared/**: the project repository can close a project without
      rewriting the whole row.
      Test: a repository test asserting the close survives a concurrent change
      to another field of the same project.
- [x] **shared/**: closed projects leave the project list.
      Test: a repository test asserting a closed project is absent from the
      project listing and still readable by id.
- [x] **shared/**: the close operation closes the project's still-open direct
      tasks and the project itself, and stops without writing anything when one
      of those tasks is blocked.
      Test: a use-case test per case — the cascade closes the open tasks and
      the project; a blocked task yields a "needs confirmation" answer with
      nothing written; confirming writes; tasks of another project and
      already-closed tasks are left alone.
- [x] **shared/** + **desktopApp/**: the project's overflow menu offers
      closing, and the confirmation states what the cascade will close.
      Test: a desktop flow test asserting the menu item exists and the sheet
      says what the close will close.

## Phase 5 — the new state travels

- [x] **shared/**: the project's closed timestamp is part of the synchronised
      state and of the backup.
      Test: the synchronisation contract test passes with the new field, the
      generated allow-list seed matches the generator, and a backup round trip
      preserves the timestamp.

## Phase 6 — verification

- [x] **shared/** + **desktopApp/**: run the full gate set — the JVM suite with
      every tag, the script gates, the linter, and the desktop UI suite.
      Test: all four green.
      Result: `:shared:jvmTest -Ptest.tags=fast,slow` — 405 classes, green;
      `:desktopApp:test -Ptest.tags=fast,slow` — 58 classes / 191 tests, green;
      `just lint` — 0 detekt findings; `scripts/ci/static-gates.sh` — 30 passed,
      4 failed, all four pre-existing on a clean tree (the gate registries and
      the Maestro calendar selectors — unrelated to this change).
