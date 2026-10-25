## 1. The governance trail

- [x] The OpenSpec change exists with its proposal, its design and its spec
      deltas, and the decision it rests on is written down in
      `docs/decisions/2026-10-25-the-refresh-is-one-run.md`
- [x] The spec-kit issue record for the change exists at
      `.specify/issues/the-refresh-is-one-run.yaml`
- [x] The decision corpus validates (`singularity-adr validate`)

## 2. The owner of the run

- [x] shared/ — a package in the core holds the whole refresh: the result, the
      state, the event, the owner of the run, the shared view model and the
      shared container
- [x] shared/ — the owner runs the archive step before the synchronisation step,
      and returns what happened; the synchronisation's own verdict travels in
      the result unchanged
- [x] shared/ — the run executes in the owner's own scope, so a refresh the user
      walked away from still finishes
- [x] shared/ — a request that arrives while a run is going joins that run
      instead of starting another
- [x] shared/ — the owner is bound in a module of its own and that module is
      aggregated with the other per-domain modules
- [x] Verified by `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt`:
      the archive step runs before the synchronisation step, the run survives the
      caller, and two concurrent requests produce one run

## 3. The archive step reaches the core through its own narrow contract

- [x] shared/ — the core declares the contract the archive step needs, and the
      archive feature's own port inherits it
- [x] shared/ — the archive step's implementation does not change by a line
- [x] shared/ — the archive feature's port is bound to the contract the core
      asks for
- [x] Verified by `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt`:
      the owner reaches the archive step through the contract and receives the
      number of tasks that moved

## 4. The gesture on one screen

- [x] shared/ — the shared container offers the gesture, shows the indication
      that a refresh is running, and shows the report
- [x] shared/ — the shared view model of the refresh holds the running state and
      delivers the result once
- [x] shared/ — the report is delivered to the screen that asked for it, and the
      screen that asked keeps it while the user is away
- [x] shared/ — the agenda wraps its content in the shared container
- [x] desktopApp/ — the gesture reaches the owner, and the report appears, on
      the agenda
- [x] Verified by `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshableContentTest.kt`:
      the gesture reaches the owner and the report appears in the notification
      area
- [x] Verified by `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshViewModelTest.kt`:
      the state goes idle → running → idle, the result arrives as an event, and
      the running state is cleared even when the refresh fails

## 5. The report says what happened

- [x] shared/ — one mapping turns a result into the message the user reads, and
      every screen uses it
- [x] shared/ — a refresh that moved nothing says so, and is not an error
- [x] shared/ — a refresh that could not reach the server says that it did not,
      says why in plain words, and says that the archive step already happened
- [x] shared/ — a refresh on a device with no account says that there was
      nothing to synchronise with, and is not reported as a failure
- [x] Verified by `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshOutcomeMessageTest.kt`:
      one case per row of the message table

## 6. The archive state leaves the device within the same refresh

- [x] shared/ — the archived tasks are written to the outgoing queue by the
      archive step, before the synchronisation cycle reads the queue
- [x] Verified by `shared/src/jvmTest/kotlin/com/singularity/todo/feature/archive/TaskDaoArchiveRepositoryImplTest.kt`:
      the archived tasks are queued for sending, which is what carries the
      archive to the server inside the same refresh

## 7. The gesture on the remaining screens

- [x] shared/ — the menu destinations, the saved agenda views and the detail
      screens wrap their content in the shared container
- [x] shared/ — the task detail and the pomodoro reach the gesture through the
      vertical scroll the screen owns, and the pomodoro's content does not jump
      when it appears
- [x] shared/ — the calendar's gesture is reachable at the top of the hour grid
- [x] shared/ — the archive screen keeps its own explicit "archive all
      completed" action, and the refresh is added beside it
- [x] Verified by `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshScrollAnchorTest.kt`:
      the gesture is reachable on the two screens that gained a scroll, and the
      content does not jump
- [x] Verified by `scripts/find-unwired-surfaces.py`: the shared container is
      called from every screen in scope — an implemented surface that nothing
      calls is the defect this scan exists to catch

## 8. The documentation and the gates

- [x] The synchronisation document describes the refresh as one of the ways a
      synchronisation starts, and the change to that document goes in the same
      commit
- [x] The decision digest is regenerated after the decision lands
- [x] The validation scenarios of `specs/015-pull-refresh-sync-archive/quickstart.md`
      are run and their outcome recorded — V-01 to V-07 passed; V-08, the two-device
      check, was not run: there is no emulator or device on this host
- [x] The full test population passes on the shared and the desktop modules
- [ ] The linters and the gate suite pass
