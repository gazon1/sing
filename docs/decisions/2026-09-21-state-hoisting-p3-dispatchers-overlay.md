---
title: state-hoisting-p3: Dispatchers, OverlayState, snackbar separation
date: 2026-09-21
status: accepted
---

# state-hoisting-p3: Dispatchers, OverlayState, snackbar separation

## Context

Phase 3 of the state-hoisting audit covered three remaining areas identified in Phase 0–2:

1. **Dispatchers.Unconfined** in `NotesListViewModel.init` block and `NoteEditor` fire-and-forget methods
2. **NotePreview** live-update regression (`collect{}` → `first()` fix)
3. **BackupScreen** mixed `events` flow handling snackbar + error in one collector

Additionally, Phase 2 had introduced `OverlayState<S>` and `NoteLinkSheet` sealed class patterns that warranted a self-review of their application scope.

---

## Decision
### 1. Dispatchers.Unconfined: init blocks → default; fire-and-forget left alone

**Problem:** `Dispatchers.Unconfined` in `init { scope.launch(Dispatchers.Unconfined) { collect{} } }` blocks causes `UncompletedCoroutinesError` in tests — `Unconfined` runs `collect{}` immediately on the test thread, blocking the test coroutine forever.

**Decision:**
- `NotesListViewModel.init` block: migrate `Dispatchers.Unconfined` → `scope.launch()` (default dispatcher). The `collect{}` here is a long-running collector and needs proper thread management.
- `NoteEditor` fire-and-forget methods (`openEditor`, `saveNow`, `scheduleAutosave`, `improveNote`): **leave on `Unconfined`**. These are one-shot operations that emit to existing `StateFlow`s or `SharedFlow`s, not long-running `collect{}` loops. `Unconfined` is correct and avoids scheduler overhead.
- `NotesListViewModel.deleteSelected`, `createNoteWithTitle`, `delete`: **leave on `Unconfined`**. Same reasoning — fire-and-forget, not `collect{}`.

**Rationale:** The distinction between "init block with `collect{}`" vs "fire-and-forget method" is critical. Only the former causes `UncompletedCoroutinesError`. Migrating fire-and-forget methods to `Default` adds scheduler overhead for no benefit.

**Consequences:**
- `NotesListViewModel` init block now uses default dispatcher — correct behavior in production and tests.
- `NoteEditor` tests added (`NoteEditorTest`) to prevent accidental migration of fire-and-forget methods to `Default`.

---

### 2. NotePreview `loadNote`: keep `first()`, not `collect{}`

**Problem:** Phase 3 changed `loadNote` from `first()` to `collect{}` to support live Room updates. This caused `UncompletedCoroutinesError` in tests with `backgroundScope`.

**Investigation:**
- `collect{}` with `scope = backgroundScope` (JVM test): `backgroundScope` runs on a separate `TestDispatcher` from `runTest`'s. `advanceUntilIdle()` drains only the test scheduler, not `backgroundScope`'s — the emission never syncs with the test assertion.
- `collect{}` with `scope = this` (same `TestScope`): the VM's collector runs on the test's `TestDispatcher`, but `advanceUntilIdle()` completes before the `collect{}` coroutine processes the emission (FakeNotesRepository emits on a different dispatcher).
- `first()` works reliably but doesn't react to live updates.

**Decision:** Keep `first()`. Live Room update propagation is a secondary concern; test stability is primary.

**Rationale:**
- The original behavior (before Phase 3) already used `first()`.
- `backgroundScope` doesn't solve the timing problem for `collect{}` with `advanceUntilIdle()`.
- A proper live-update test would require controlling `FakeNotesRepository`'s emission dispatcher, which is outside the scope of this fix.

**Trade-off documented:** Preview screen does not react to live note changes within a session. This is acceptable — preview is read-only and typically navigated to fresh each time.

---

### 3. BackupScreen snackbar: separate `SharedFlow<String>` from `BackupUiEvent`

**Problem:** `BackupUiEvent.ShowSnackbar` mixed success notifications with error events in one flow. `NotificationHost` was consuming this flow and mapping `ShowSnackbar → Notification.None`, while a separate `LaunchedEffect(events.collect{})` handled the snackbar presentation. Two collectors on the same `events` flow is error-prone.

**Decision:**
- Remove `BackupUiEvent.ShowSnackbar`. Success is not an error.
- Add `BackupViewModel.snackbar: SharedFlow<String>(extraBufferCapacity = 4)` — project convention for one-shot event flows.
- `BackupScreen`: dedicated `LaunchedEffect(snackbar) { snackbar.collect { showSnackbar } }` for snackbars; `NotificationHost` handles errors only.
- `SettingsScreen.BackupScreenWrapper`: passes `backupVm.snackbar` as new parameter.

**Rationale:** Material 3 separation — dialogs (blocking/modal) via `NotificationHost` vs snackbars (transient/non-blocking) via dedicated `SnackbarHostState`. Each has its own collector and lifecycle.

**Consequences:**
- `BackupUiEvent` now only contains `Error` — semantic clarity.
- `BackupViewModelTest` updated to remove `ShowSnackbar` references; new test added for snackbar emission.
- `BackupScreen` signature changed (added `snackbar` parameter) — all call sites updated.

---

### 4. YAGNI: do NOT add `BackupScreenActions` value class

**Self-review finding:** Plan v2 proposed adding `@JvmInline value class BackupScreenActions` for 6 callbacks. Per `singularity-todo-shared-ui-components` skill: "Do NOT introduce a new Actions class for 4 callbacks in a single-screen composable — raw lambdas are fine until usage spreads to 2+ screens."

**Decision:** Drop `BackupScreenActions`. 6 raw lambda parameters are acceptable for a single screen. Add the value class later if a second screen needs the same callbacks.

---

### 5. YAGNI: do NOT use `OverlayState<Nothing>` for BackupScreen snackbar

**Self-review finding:** Plan v2 proposed `OverlayState<Nothing>()` for BackupScreen's snackbar management. `Nothing` as a type parameter doesn't compile (`Nothing : Any` is false).

**Decision:** Use plain `remember { SnackbarHostState() }` since BackupScreen has no sheet/dialog — only a snackbar host. No `OverlayState` needed.

---

## Files changed

| File | Change |
|---|---|
| `NotesListViewModel.kt` | `init` block: `Unconfined` → default |
| `NoteEditorTest.kt` | New: 3 smoke tests with `PausableAutosaveScheduler` |
| `NotePreview.kt` | `loadNote` keeps `first()` (not `collect{}`) |
| `NotePreviewTest.kt` | Moved to `jvmTest`; 6 tests pass with `scope = this` |
| `NotePreviewScreen.kt` | `snapshotFlow + debounce(100ms)` for body re-render |
| `BackupUiEvent.kt` | Removed `ShowSnackbar` |
| `BackupViewModel.kt` | Added `snackbar: SharedFlow<String>`; 4 emit sites updated |
| `BackupScreen.kt` | Dedicated snackbar collector; `NotificationHost` for errors only |
| `SettingsScreen.kt` | Passes `backupVm.snackbar` to `BackupScreen` |
| `BackupViewModelTest.kt` | Removed `ShowSnackbar`; added snackbar emission test |

---

## Links

- Skill: `singularity-todo-coroutine-scopes` — dispatcher conventions
- Skill: `singularity-todo-shared-ui-components` — `OverlayState`, snackbar vs dialog separation
- Skill: `singularity-todo-ui-event-vs-state` — success is one-shot event, not state
- Skill: `singularity-todo-test-helpers` — `backgroundScope`, `advanceUntilIdle`, test patterns
