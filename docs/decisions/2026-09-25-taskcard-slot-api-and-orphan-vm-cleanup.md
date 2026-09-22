---
status: accepted
date: 2026-09-25
---

# TaskCard slot API refactor + TasksViewModel final cleanup

## Context

Two orthogonal cleanup passes completed in the same window:

1. **`TasksViewModel` (and its full orbit) was already slated for deletion** in
   [`2026-09-16-agenda-engine.md`](./2026-09-16-agenda-engine.md) ("Удаляются:
   TasksViewModel"). The mandate was scoped but never executed for the VM file
   itself — `TasksViewModelTest` continued to test it, the DI binding in
   `TasksDiModule.kt` kept registering it, and a dead `BulkActionBar.kt` /
   `FilterChipsRow.kt` lived alongside it.

2. **`TaskCardActions` was a `@JvmInline value class` wrapping a single
   `(Action) -> Unit` block**. The companion `Empty = TaskCardActions {}` made
   every callback a no-op. Three screens (`ProjectDetailScreen`,
   `SearchScreen`, `ArchiveScreen`) passed `Empty` unconditionally, so the
   pin / AI / delete buttons on `TaskCard` were rendered but did nothing —
   a broken UX pattern that looked interactive but silently dropped user
   taps.

Additionally the `TaskDetailViewModel` had four legacy imperative entry points
(`onTitleChange`, `onDescriptionChange`, `lastEditedAt`, `recentlyDeleted`)
left behind by the sealed-`TaskDetailIntent` migration in `7ac61c8`. None were
reachable from the screen (which had migrated to `vm.onIntent(Domain.*)`),
but detekt's `UnusedPublicProperty` and `UnusedPrivateProperty` rules flagged
them as warnings.

---

## Decisions

### 1. Complete the `TasksViewModel` deletion

Execute the existing mandate from `2026-09-16-agenda-engine.md:113`:

- **Deleted:** `viewmodel/TaskList.kt`, `TasksViewModelTest.kt`,
  `components/BulkActionBar.kt`, `components/FilterChipsRow.kt`.
- **Renamed:** `domain/model/TaskList.kt` → `domain/model/TaskAi.kt`. The
  source file's name referenced the dead `TasksList.kt` model; only the AI
  types (`AiActionResult`, `TaskAiAction`, `formatAiResult`) had live
  consumers. Six orphaned types (`TasksUiEvent`, `TaskDetailUiEvent` dup,
  `TaskEditorUiEvent`, `TaskGroup`, `TasksUiState`, `TasksScreenEntry`)
  removed from the model file.
- **DI:** `TasksViewModel` import + `viewModel { ... }` block removed from
  `TasksDiModule.kt`. `CreateTaskUseCase` / `UpdateTaskUseCase` retained
  (consumed by `TaskDetailDeps`).
- **Stale docstrings fixed:** `NotificationHost.kt` and `core/ui/components/UiEvent.kt`
  referenced the deleted `TasksUiEvent` type — replaced with the canonical
  `TaskDetailUiEvent` path. `FakeCurrentUser.kt` example swapped from
  `TasksViewModel(...)` to `TaskDetailViewModel(...)` (later dropped entirely
  in the P7 cleanup).

### 2. `TaskCardActions` → Slot API (canonical Kotlin/Compose pattern)

**Before:** `@JvmInline value class` with a sealed `Action` enum, single
`(Action) -> Unit` block, `Empty = {}` no-op sentinel.

**After:** Plain `@Stable class` with four explicitly nullable callbacks
(`onToggle`, `onPin`, `onAiClick`, `onDelete`). The card conditionally renders
each button via `actions.onX?.let { ... }`. `Empty` companion dropped in a
follow-up cleanup (P4) — callers use the no-arg constructor `TaskCardActions()`.

**Rationale:**

- The single-block + enum pattern is convenient for the producer (one
  parameter) but is an attractive nuisance for consumers: any screen can
  pass `Empty` and silently produce a no-op UI. The cost of this convenience
  is real broken UX.
- Slot API makes "I forgot to wire X" syntactically impossible — the
  composable just doesn't render the button. Defensive by construction.
- Matches the canonical Kotlin/Compose pattern for component customization:
  explicit nullable callbacks + conditional rendering. Same pattern as
  `Material3.Card`'s `colors`, `elevation`, `border` slots.

**Trade-off:** Every screen must now decide per-callback whether to wire it
(`actions = TaskCardActions(onPin = { ... }, onDelete = { ... })`) instead of
always passing `Empty`. The added verbosity is the price of correctness.

### 3. Wire real handlers per screen context (semantic correctness)

After the slot API refactor, the per-screen wiring encodes semantic intent:

| Screen | onToggle | onPin | onAiClick | onDelete |
|---|---|---|---|---|
| `ProjectDetailScreen` | n/a | ✅ | ❌ | ✅ |
| `SearchScreen` | n/a | ✅ | ❌ | ❌ |
| `ArchiveScreen` | ❌ | ❌ | ❌ | ❌ |

`ArchiveScreen` deliberately passes `TaskCardActions()` — archived tasks are
not pinnable, not deletable from the card, and the toggle checkbox is
meaningless (the restore action lives in a dedicated flow). `SearchScreen`
intentionally exposes only `onPin` — AI/delete from search results is
unexpected behavior.

`SearchViewModel` was extended with `taskRepo: TaskRepository` constructor
parameter and a `togglePin(taskId)` method using the project's
`fireAndForget(errorLabel, onError) { ... }` helper. `ProjectDetailViewModel`
gained `Domain.ToggleTaskPin` and `Domain.DeleteTask` intents, dispatched
via the existing `ProjectDetailActions` value class.

### 4. Drop dead `TaskDetailViewModel` entry points

- `val lastEditedAt` + private `_lastEditedAt` field — was set by three
  `.onSuccess { _lastEditedAt.value = deps.clock.now() }` sites, but no
  consumer ever read it. Removed together with the field and the writes.
- `val recentlyDeleted` public — undo uses
  `TaskDetailUiEvent.UndoDelete(current.id)` (one-shot channel), not this
  flow. Public accessor removed; private `_recentlyDeleted` field retained
  because the `Restore` intent cycle still reads it internally.
- `fun onTitleChange(value)` / `fun onDescriptionChange(value)` — replaced
  by `TaskDetailIntent.Domain.TitleChanged` / `DescriptionChanged`. The
  `onIntent(intent)` body does the same work; the screen already routes
  through `vm.onIntent(...)`.
- Dead `silent: Boolean = false` parameter in `mutate()` removed in P1
  cleanup (it guarded the deleted `_lastEditedAt` write).

### 5. Surface pin errors in SearchScreen via Snackbar

`SearchViewModel.togglePin` emits `SearchUiEvent.Error` to a `SharedFlow`
that no collector in `SearchScreen` was observing — failures were silently
dropped. Added `SnackbarHostState` + `LaunchedEffect { events.collect ... }`
to `SearchScreen`. Now `Pin failed: <message>` is shown to the user.

### 6. Tighten `ProjectDetailActions.Empty` visibility

`ProjectDetailActions.Empty` was a public companion making all 22 dispatched
intents no-op. Mirrors the same `Empty`-as-footgun risk that the slot API
refactor fixed for `TaskCardActions`. Slot-API refactor of
`ProjectDetailActions` is out of scope (22 nullable fields = disproportionate
blast radius), so we settled for **visibility restriction**:
`internal val Empty` — only the shared module can construct it. External
consumers (`androidApp`, `desktopApp`) cannot accidentally use the no-op
dispatcher in production code anymore.

KDoc updated with an explicit warning that `Empty` is for previews/tests only.

---

## Consequences

### Positive

- **UX honesty**: rendered buttons do what they advertise. No more
  silent-drop user taps.
- **Detekt clean**: 14 false-positive warnings gone; baseline shrinks.
- **Smaller public surface**: `-880 / +120` lines net; 5 files deleted;
  1 file renamed to match its actual content.
- **ADR `2026-09-16-agenda-engine.md` mandate completed** — TasksViewModel
  and its orbit removed.
- **Type-safe UX expectations**: each screen's `TaskCardActions(...)`
  instantiation is a self-documenting contract of what that screen allows.

### Negative / Trade-offs

- **`TaskCardActions` API is a breaking change** for any external consumer
  of the shared module. Mitigated by the fact that this is an internal KMP
  module consumed only by `androidApp` and `desktopApp` — both updated in
  the same commits.
- **Per-screen wiring is more verbose** — `TaskCardActions(onPin = { ... })`
  vs the old `TaskCardActions.Empty`. Acceptable.
- **`ProjectDetailActions` still uses the value-class + block pattern** —
  see decision #6 above. The risk is bounded by `internal Empty` visibility
  and KDoc warning.
- **Five commits land together** because they all touch the same orbit
  (TaskCard wiring ↔ search VM ↔ project detail VM ↔ DI graph). Each commit
  is independently revertible.

---

## Follow-ups

- **Group task-level actions in `ProjectDetailActions`** (`onPin(taskId)`,
  `onDeleteTask(taskId)`) into a nested `TaskCardActions` slot instead of
  flat methods. Low priority — premature optimization while only 2 task
  methods exist.
- **Consider `slot API` migration for the remaining value-class Actions**
  (`NoteActions`, `TaskDetailActions`, etc.). Same `Empty`-as-footgun risk
  applies but blast radius is smaller (each is typically used by 1–2 screens
  with real dispatchers).
- **Audit other `SharedFlow events` with no collector** — same silent-drop
  pattern as P3 (`SearchViewModel.togglePin`). Use
  `grep -rn "SharedFlow<" shared/src/commonMain --include="*ViewModel.kt"`
  and cross-reference with screen subscribers.

---

## Links

- [`2026-09-16-agenda-engine.md`](./2026-09-16-agenda-engine.md) — original
  mandate for `TasksViewModel` deletion (executed by this ADR).
- Commits: `75a56ed`, `005021c`, `ce16cbd`, `24ff2ff`, `ccfb2b8`.
- `singularity-todo-clean-architecture-audit` skill — verification tool
  for layer boundaries after these refactors.
- `singularity-todo-shared-ui-components` skill — slot API conventions.
