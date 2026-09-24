---
title: "State Hoisting Audit"
status: accepted
date: 2026-09-21
tags: [vm, compose, state-hoisting, refactor]
---

# State Hoisting Audit

## Context

Audit of all ViewModels and Compose screens in the `singularity-todo` KMP project
identified residual Compose-layer violations after the `AutoCloseableCoroutineScope`
migration (4 commits, 2026-09-21). The audit covered 24 ViewModels and their
consuming screens.

Common anti-patterns found:

1. **Multiple independent `remember { mutableStateOf }` flags** for sheet/menu/popup
   coordination (e.g., `linkDialogVisible`, `internalLinkPickerVisible`, `popupOpen`).
   These are not coordinated — opening one doesn't dismiss the other.
2. **Form fields not surviving process death** — text fields used `remember` instead
   of `rememberSaveable` (e.g., `LoginScreen` email/password).
3. **Public `loadNote()`, `delete()` methods** on ViewModels instead of intent dispatcher
   pattern (canonical pattern documented in `singularity-todo-vm-intent-pattern`).
4. **`Dispatchers.Unconfined`** in test-resistant locations causing
   `UncompletedCoroutinesError`.
5. **Internal links (`note://`, `task://`)** in `NotePreviewScreen` not navigating —
   `RichText` would call `UriHandler.openUri` which crashed for unknown schemes.
6. **No `@Stable` annotations** on state holders → recomposition skip optimization
   unavailable.

## Decision

Split the work into 4 stacked branches/PRs:

### Phase 0 — Working tree cleanup (no PR)

Commit all uncommitted changes (DI module fallout from AutoCloseable migration,
`NotePreview` re-entrancy fix, dead imports). Single commit on `main`.

### Phase 1 — Pre-work PR `state-hoisting-pre`

Introduce two reusable base classes in `core/ui/components`:

- `OverlayState<S : Any>` — coordinates sheet + overflow menu + snackbar via
  `mutableStateOf`; `rememberOverlayState<S>()` factory.
- `FormState<T : Any>` — abstract form holder with `value` + `update { copy() }`
  + concrete `Saver` per subclass via `listSaver` (no generic erasure issues).
- Add `@Stable` annotations to 8 existing holders:
  `DialogState`, `EditorSession`, `DraftState` (SavedAgendaVM), `TaskDetailDraftState`,
  `ProjectDetailDraftState`, `EditorAction`, `NotesActions`, `TaskCardActions`,
  `TaskMenuActions`, `ProjectCardActions`, `ProjectDetailActions`.
- Update `singularity-todo-shared-ui-components` skill with new patterns.

Tests: `OverlayStateTest` (8), `FormStateTest` (7).

### Phase 2 — P0 critical PR `state-hoisting-p0`

3 screens had P0 violations:

- `LoginScreen` — 3 `remember { mutableStateOf }` for email/password/isSignUp →
  `LoginFormState` + `rememberLoginFormState()` with concrete `Saver`. Auth VM
  error event migrated to `AuthUiEvent.Error`.
- `BackupScreen` — `rememberCoroutineScope` + `CollectEvents` for snackbar
  → `BackupUiEvent.ShowSnackbar` + `LaunchedEffect + collect`.
- `SettingsScreen` — `koinInject<FileRevealer>` + `rememberCoroutineScope` for
  folder open → `SettingsIntent.OpenAttachmentsFolder` + VM injects `FileRevealer`.

### Phase 3 — P1 architectural PR `state-hoisting-p1`

- `NoteEditorScreen` — `linkDialogVisible` + `internalLinkPickerVisible` +
  `linkUrl` + `linkQueryFlow` (4 remember flags) → `OverlayState<NoteLinkSheet>`
  (sealed class with `External` data object + `InternalPicker` data class) +
  `linkUrl` via `rememberSaveable`. (NoteLinkSheet must be sealed class, not enum,
  for Kotlin 2.0 `is` checks.)
- `ProjectDetailScreen` — `popupOpen` + `text` + `query` → `OverlayState<QuickAddSheet>`.
- `NotePreview` — `loadNote()` + `delete()` public methods → `sealed NotePreviewIntent`
  with `Load`/`Refresh`/`Delete` + `onIntent()` dispatcher.
- `NotePreviewScreen` — internal link interception via `CompositionLocalProvider`
  overriding `LocalUriHandler`; routes `note://<id>` to `onNavigateToNote`,
  `task://<id>` to `onNavigateToTask`, http(s):// to default URI handler.

Bonus fix: `NotePreview` changed from `Flow.collect { }` to `Flow.first()` —
`collect` never completes, causing test `UncompletedCoroutinesError`.

### Phase 4 — P2 cleanup PR `state-hoisting-p2`

- `NotesListVM` — `Dispatchers.Unconfined` → `Dispatchers.Default` (fixes flaky
  tests; Unconfined doesn't cooperate with `runTest`).
- `AccountSettingsViewModel` — already uses canonical pattern (thin wrapper);
  no migration needed.
- `TaskDetail.kt:103-122`, `ProjectDetailViewModel.kt:339`,
  `TaskList.kt` — side-effect assignments (`_lastEditedAt.value = ...`) are
  SUCCESS HANDLERS of mutation operations, not side effects of pure `combine`
  flows. Leave as-is — extracting would lose ordering guarantees between
  `updateTask()` completion and timestamp update.

## Rationale

- **Pre-work first** — eliminating copy-paste via tested base classes pays off
  in every subsequent phase; the `FormState.Saver` pattern handles `LoginScreen`
  and any future form.
- **Stacked branches** — each phase has a small, reviewable diff; merge order
  is explicit (pre → p0 → p1 → p2).
- **`@Stable`** — gives Compose recomposition skip; minimal cost, broad benefit.
- **Concrete `Saver` per form** — avoids generic `Saver` factory / reflection
  / erasure issues; each form declares its own `listSaver` companion.
- **`OverlayState` over `DialogState`** — `DialogState` (already exists) only
  handles a single active dialog; `OverlayState` extends to menu + snackbar
  coordination.
- **Intent dispatcher** — canonical pattern; removes public `loadNote`/`delete`
  methods that bypass the `onIntent` indirection used by 90% of VMs.

## Consequences

**Positive:**
- Testability — `NotePreviewTest`, `LoginFormStateTest`, `OverlayStateTest`,
  `FormStateTest` added.
- Recomposition skip — `@Stable` on 11 holders.
- Unified mental model for state holders.
- Internal note/task links now navigate correctly.
- `Dispatchers.Default` fixes flaky VM tests.

**Negative:**
- 4 PRs instead of 1 (review overhead).
- Pre-work required 3-4 hours before any visible feature change.
- `OverlayState` (Phase 1) is not yet saved across process death — acceptable
  trade-off per plan; reopen dialog after rotation is fine for these screens.

## Links

- [2026-09-21-auto-closeable-coroutine-scope](./2026-09-21-auto-closeable-coroutine-scope.md)
- [2026-09-18-vm-scope-cancellation-oncleared](./2026-09-18-vm-scope-cancellation-oncleared.md) (superseded)
- [2026-09-15-viewmodel-state-ownership](./2026-09-15-viewmodel-state-ownership.md)
- [2026-09-18-shared-ui-adoption-mr5](./2026-09-18-shared-ui-adoption-mr5.md)
- `singularity-todo-testable-vm`
- `singularity-todo-shared-ui-components`
- `singularity-todo-ui-event-vs-state`
- `singularity-todo-vm-intent-pattern`
- `singularity-todo-coroutine-scopes`
