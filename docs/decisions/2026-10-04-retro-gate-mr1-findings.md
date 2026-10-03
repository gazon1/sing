# Retro-gate MR-1: findings and follow-up decisions

## Context

After completing MR-1 batch fixes, a retro-gate review identified the following
architectural observations, known limitations, and deferred items.

## Decisions

### 1. Undo snackbar: no countdown timer in LaunchedEffect

**Observation:** `LaunchedEffect(pendingDelete)` only re-triggers when the `PendingDelete`
value changes (non-null → non-null is a no-op). There is no visual countdown on the
snackbar button.

**Decision:** Accept current implementation. A countdown timer requires either:
- `SnackbarHostState.showSnackbar` returning `SnackbarResult.ActionPerformed` after the
  action is tapped (not after duration expires), or
- A custom `Snackbar` composable with animated remaining-time indicator.

Both are future work; the 5-second fixed window is sufficient for the undo contract.

### 2. Scaffold scope limitation in TaskDetailViewScreen

**Observation:** Adding a `Scaffold` wrapper to `TaskDetailViewScreen` fails because
private composables (`LoadingState`, `ErrorState`, etc.) are defined at file level and
become inaccessible inside the Scaffold's `content` lambda (a different lexical scope).

**Decision:** TaskDetailViewScreen keeps `Box` structure. Snackbars for Saved events
use the `Notification.None` pattern (mapper returns `None`, UI subscribes via
`LaunchedEffect` on `coordinator.events`).

Future work: extract private composables into a separate internal composable function
or a dedicated file-level `TaskDetailScaffold` that takes the snackbar host as parameter.

### 3. AgendaViewModel undo: no retry on failed restore

**Observation:** `onUndoDelete` calls `deps.taskRepo.restore(taskId)` but if it fails,
the `PendingDelete` state is already cleared (`null`) and the snackbar has dismissed.
The user is not notified of the failure.

**Decision:** Log error and emit a generic error notification. This is a minor UX gap
— restore is unlikely to fail for a task that was just deleted (the row exists). Future
work: keep `PendingDelete` state on error and show error snackbar with retry.

### 4. BackupImporter: DAO bypass (known debt)

**Observation:** `BackupImporter` writes directly to DAOs, bypassing repositories, to
target `options.targetUserId` without triggering `assertCanWrite` guards. This is
documented in the class KDoc with a reference to `docs/decisions/2026-09-27-write-layer-soundness.md`.

**Decision:** Accept as documented technical debt. Proper fix: a dedicated bulk-import
port that takes an explicit userId. Not in scope for MR-1/MR-2.

### 5. SectionPrefill date: hardcoded from AGENDA_SEED

**Observation:** `AgendaPresets.Inbox` sections use `LocalDate(2026, 10, 3)` etc.
These are compile-time constants in an `object`. If the actual date differs from
the seed, prefill dates are incorrect.

**Decision:** Documented in `docs/decisions/2026-10-03-section-prefill-for-preset-sections.md`
as `section-prefill-dynamic-date` in backlog. Future work: `DueDateOption.Relative`
or clock-aware preset factory.

### 6. FakeClock in desktop harness: wired but not yet used

**Observation:** ADR exists (`2026-10-03-fake-clock-in-desktop-harness.md`). The
`fakeClock: FakeClock? = null` parameter was added to `runDesktopAppTest` but the
actual wiring in `harness/testHarness` was not verified end-to-end in MR-1.

**Decision:** MR-2 task `FakeClock in harness` will verify and complete this.

## Verified Flows (MR-1)

| Flow | Status |
|---|---|
| `handleCreateInSection` → `DraftStore` → `TaskCreateViewModel` reads draft | ✅ Verified |
| Undo delete: `onUndoDelete` → `taskRepo.restore()` → `observeByFilter` re-emits | ✅ Flow-based re-evaluation works |
| Snackbars: `LaunchedEffect` pattern in TaskDetailContent, SavedAgendaListScreen | ✅ Working |
| Backup export: `AgendaViewDao.listAllForUser` → `AgendaViewDto` → payload | ✅ Working |
| Backup import: `payload.agendaViews` → `AgendaViewDao.upsert` | ✅ Working |
| `SavedAgendaViewModel.isSaving` preservation | ✅ Fixed |

## Deferred to Backlog

| Item | Description | Ticket |
|---|---|---|
| `section-prefill-dynamic-date` | Dynamic prefill dates via `DueDateOption.Relative` or clock-aware factory | deferred-backlog |
| `undo-restore-failure-notify` | Restore failure leaves no trace; add error snackbar | deferred-backlog |
| `task-detail-scaffold-refactor` | Extract `TaskDetailViewScreen` to use Scaffold properly | deferred-backlog |
| `countdown-snackbar` | Visual countdown on undo snackbar | deferred-backlog |
| `bulk-import-port` | Proper DAO bypass fix for BackupImporter | deferred-backlog |
