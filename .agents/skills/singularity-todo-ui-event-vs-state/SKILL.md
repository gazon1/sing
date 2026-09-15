---
name: singularity-todo-ui-event-vs-state
description: How to model one-shot UI events and state ownership separately from continuous UI state in Singularity Todo ViewModels. Use when adding a MutableStateFlow for a dialog flag inside a Composable, when LaunchedEffect.collectLatest is used to format a domain result into a string, when remember mutableStateOf shadows VM-owned state, or when a screen has both a state StateFlow and a SharedFlow for AI results that should be merged. Documents three categories of UI data, the per-feature UiEvent sealed interface, Pulse events, CollectEvents, routing state, draft ownership, and the mirror-state anti-patterns.
---

# UI Event vs UI State — The Singularity Todo Pattern

Compose has a notorious pitfall: every `var foo by remember { mutableStateOf<X?>(null) }` inside a Composable is **second state** that mirrors what's already in the ViewModel. This pattern spreads logic across two layers, makes the screen harder to test, and forces every screen to re-invent the same `LaunchedEffect { vm.X.collectLatest { localState = ... } }` plumbing.

This project standardises on a **three-category split** for all UI-related data. The categories are mutually exclusive:

| Category | Lives in | Replay on rotation? | Read pattern |
|---|---|---|---|
| **UI state** (the list of tasks, the current filter, the editor state, loading branch) | `StateFlow<UiState>` on the VM | Yes — the new collector sees the latest value | `collectAsStateWithLifecycle()` |
| **One-shot events** (show "AI Result" dialog, navigate back, snackbar, saved pulse) | `SharedFlow<UiEvent>` on the VM | **No** — events fire once | `CollectEvents(vm.events) { … }` → `ResultDialog` / navigation lambda |
| **Routing state** (which sheet/dialog/menu is open) | `remember { mutableStateOf }` in Composable | No — transient UI affordance | Local to the composable |

Composable exceptions that are **not** routing state:
- `Animatable` / `animateFloatAsState` for one-shot UI animations (saved-pill fade, etc.)
- `MutableStateFlow<String>` used as a **write-port** — e.g. `queryFlow` in a search field. The Composable writes to it, the VM reads from it via `debounce + flatMapLatest`. This is a communication channel, not a duplicate of VM state.
- Transient TextField input **before** `onValueChange` fires — the user is still typing and has not yet sent the value to the VM.

## The rule (enforced)

**Domain state must live in the ViewModel.** No `remember { mutableStateOf }` that copies a value already present in a VM `StateFlow`. No `LaunchedEffect` that mirrors VM state into a local variable. No repository calls from inside a Composable. The Composable subscribes; the ViewModel owns.

See `docs/decisions/2026-09-15-viewmodel-state-ownership.md` for the canonical decision record with the full rationale and anti-pattern catalog.

## ⚠️ Per-feature UiEvent (not global)

Every feature has its **own** `sealed interface XxxUiEvent` — not a shared global `UiEvent`. This prevents naming conflicts when multiple screens emit `NavigateBack` or `ShowError`.

```kotlin
// ✅ CORRECT — per-feature sealed interface
sealed interface NotesUiEvent {
    data object NavigateBack : NotesUiEvent
    data class SaveFailed(val message: String) : NotesUiEvent
    data class AiResult(val text: String) : NotesUiEvent
    data object SavedPulse : NotesUiEvent  // Phase 1: one-shot saved indicator
}

sealed interface TasksUiEvent {
    data object NavigateBack : TasksUiEvent
    data class ShowError(val message: String) : TasksUiEvent
    // ... feature-specific events
}
```

```kotlin
// ❌ WRONG — global UiEvent (deprecated, causes naming conflicts)
sealed interface UiEvent {
    data class ShowDialog(val title: String, val text: String) : UiEvent
    data object NavigateBack : UiEvent
    // ...
}
```

**Migration:** All screens have been migrated to per-feature events (ADR `2026-09-05-ui-event-per-feature`). Do not reintroduce a global `UiEvent`.

## Pulse Events (one-shot signals without payload)

Some events carry no data — they are just a signal to trigger a UI animation or reaction. Use `MutableSharedFlow` with `extraBufferCapacity = 1` and `replay = 0`:

```kotlin
// NotesViewModel — Saved pulse (Phase 1)
private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

private fun scheduleAutosave(id: String) {
    autosaveJob?.cancel()
    autosaveJob = scope.launch(Dispatchers.Unconfined) {
        autosaveScheduler.awaitTick()
        val current = _editorState.value as? EditorState.Editing ?: return@launch
        try {
            repo.updateContent(...).getOrThrow()
            _editorState.value = current.copy(isDirty = false)
            _savedPulse.emit(Unit)  // ← triggers "Saved" animation in UI
        } catch (e: Exception) {
            _events.emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        }
    }
}
```

**Screen side — dedup with Animatable:**
```kotlin
@Composable
fun NoteEditorScreen(...) {
    val savedAlpha = remember { Animatable(0f) }

    LaunchedEffect(state.id) {
        vm.savedPulse.collect {
            // Animate: fade in → hold → fade out
            savedAlpha.snapTo(1f)
            delay(800)
            savedAlpha.animateTo(0f)
        }
    }

    // Render pill with animated alpha:
    if (savedAlpha.value > 0.01f) {
        Text(
            "Saved",
            modifier = Modifier.graphicsLayer { alpha = savedAlpha.value },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
```

**Why not `mutableStateOf<String?>` for the pill?** Because that would duplicate the VM's knowledge of when a save happened. The screen should only react to the VM's event, not maintain its own mirror state.

## The `CollectEvents` helper

Always use `CollectEvents(flow) { … }` instead of `LaunchedEffect(Unit) { vm.events.collectLatest { … } }`:

```kotlin
@Composable
fun NoteEditorScreen(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    onAiClick: () -> Unit,
) {
    CollectEvents(vm.events) { event ->
        when (event) {
            is NotesUiEvent.NavigateBack -> onBack()
            is NotesUiEvent.SaveFailed -> { /* show error */ }
            is NotesUiEvent.AiResult -> { /* show AI result dialog */ }
            NotesUiEvent.SavedPulse -> { /* handled by savedAlpha Animatable above */ }
        }
    }
}
```

`CollectEvents` is a thin wrapper:
```kotlin
@Composable
fun <T : UiEvent> CollectEvents(
    flow: SharedFlow<T>,
    onEvent: (T) -> Unit,
) {
    LaunchedEffect(flow) {
        flow.collect { onEvent(it) }
    }
}
```

## Why this matters

1. **The VM stays source of truth.** No way for the Composable to drift out of sync.
2. **Formatting is testable without Compose.** `formatAiResult(...)` is a pure function.
3. **The screen is thin.** No `LaunchedEffect`, no local state for domain events.
4. **The VM is testable without Compose.** `vm.events.test() { … }` from a `runTest` block.

## Test recipe for VM events

```kotlin
@Test
fun `saveNow emits NavigateBack on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.saveNow()
    advanceUntilIdle()
    val event = vm.events.first()
    assertIs<NotesUiEvent.NavigateBack>(event)
}

@Test
fun `scheduleAutosave emits SavedPulse on success`() = runTest {
    val vm = createVm(repo = FakeNotesRepository().apply { seed(note) })
    vm.editTitle("n1", "Updated title")
    // simulate autosave completion
    val pulse = vm.savedPulse.first()
    assertIs<Unit>(pulse)  // SavedPulse is a data object
}
```

## Anti-patterns

**Do NOT use `mutableStateOf` for domain-derived strings:**
```kotlin
// ❌ WRONG — mirrors VM state in Composable
@Composable
fun TasksScreen(...) {
    var aiResultText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.aiResult.collectLatest { result ->
            aiResultText = when (result) {
                is AiActionResult.RefineTitle -> "Refined: ${result.newTitle}"
                // ...
            }
        }
    }
}

// ✅ CORRECT — format in VM, emit via event, render in ResultDialog
// ViewModel:
_events.emit(NotesUiEvent.AiResult(formatAiResult(result)))

// Screen:
CollectEvents(vm.events) { event ->
    if (event is NotesUiEvent.AiResult) dialogText = event.text
}
ResultDialog("AI Result", dialogText) { dialogText = null }
```

**Do NOT use Pulse events for replayable state:**
```kotlin
// ❌ WRONG — savedPulse should not replay; use StateFlow for replayable saves
val lastSaveTime: StateFlow<Instant?> = MutableStateFlow(null)

// ✅ CORRECT — savedPulse is one-shot, extraBufferCapacity = 1
val savedPulse: SharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
```

## Routing State — The Third Category

There are **three** kinds of UI-related data, not two:

| Category | Where it lives | Example |
|---|---|---|
| **UI state** | `StateFlow<UiState>` on VM | task list, filter, loading branch |
| **One-shot events** | `SharedFlow<UiEvent>` on VM | snackbar, dialog, navigate back |
| **Routing state** | `remember { mutableStateOf(...) }` on screen | `activeSheet: ActiveSheet?`, `menuExpanded: Boolean` |

Routing state is the **which sheet / dialog / menu is currently open**. It belongs on the screen, not in the VM, because:
- It is Composable-local (only the current screen cares).
- It has no persistence requirement (rotating the device closes the sheet).
- Routing decisions are cheap and local (no repository call needed).

**The anti-pattern: routing through the VM's SharedFlow.**

```kotlin
// ❌ WRONG — opening a date picker goes through the VM
// TaskDetailViewModel:
fun openDatePicker() = scope.launch { _events.emit(TaskDetailUiEvent.OpenDatePicker) }
// TaskDetailScreen:
CollectEvents(vm.events) { event ->
    if (event is TaskDetailUiEvent.OpenDatePicker) activeSheet = ActiveSheet.Date
}

// ✅ CORRECT — screen owns routing state directly
// TaskDetailActions helper:
fun onPickDate() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Date))
// TaskDetailScreen:
val actions = remember { TaskDetailActions { intent ->
    when (intent) {
        is TaskDetailIntent.OpenSheet -> activeSheet = intent.sheet
        // ...
    }
} }
```

`ConfirmDelete`/`ConfirmArchive` in the original `TaskDetailScreen` were already doing this correctly — the round-trip was unnecessary.

**Exception: routing that requires domain data.** If you need to ask the VM which project to pre-select in a picker, read that from `state.ui.project` (already available). You do not need to emit a `SharedFlow` event to ask.

**ProjectDetailIntent follows the same pattern.** Routing variants (`OpenColorSheet`, `OpenIconSheet`, `OpenDeleteSheet`, etc.) are `Routing` sealed-subinterface members. They are handled on the screen via `remember { ProjectDetailActions { ... } }` that sets `activeSheet = ActiveSheet.PickColor` directly. The VM never knows which sheet is open.

```kotlin
// ProjectDetailScreen — routing handled entirely on screen
val actions = remember {
    ProjectDetailActions { intent ->
        when (intent) {
            is ProjectDetailIntent.Routing.OpenColorSheet -> { sheetState = ActiveSheet.PickColor }
            is ProjectDetailIntent.Routing.OpenDeleteSheet -> { sheetState = ActiveSheet.ConfirmDelete }
            is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
        }
    }
}
```

## When to keep `MutableStateFlow` inside a Composable

Genuinely Composable-local UI affordances only:
- `var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }` — UI routing, not domain
- `var searchQuery by remember { mutableStateOf("") }` — local to a search field before submission
- `Animatable` for one-shot UI animations (saved pill fade, etc.)

What must **not** live in a Composable:
- Anything derived from a VM `StateFlow` (duplicated state)
- Domain formatting (`when (result)` that produces user-facing strings)
- Anything other Composables on the same screen also need

---

## State Ownership Boundary

The three categories above are **mutually exclusive**. The test for any piece of state is: "Does this come from a repository or use case? Does more than one Composable on the screen need it? Is it computed from VM state?" — if any answer is yes, it belongs in the VM.

### The ownership table

| Category | Where it lives | Example |
|---|---|---|
| Domain state (lists, filters, current entity, domain-derived values) | VM `StateFlow` | `state.tasks`, `_filter`, `_selectedIds`, `recentlyDeleted` |
| Snapsnot from repo `Flow` | VM `StateFlow` via `combine + flatMapLatest` | `projectNamesFlow`, `parentOptionsFlow` |
| Input draft (TextField value before `onValueChange` fires) | VM `MutableStateFlow<String>` alongside `_latest<Entity>` in the same `combine` | `_draftTitle`, `_draftDescription` |
| Routing state | Composable `mutableStateOf` | `activeSheet: ActiveSheet?`, `menuExpanded`, `linkDialogVisible` |
| Animation | Composable `Animatable` / `animateFloatAsState` | saved-pill alpha |
| Business logic (filter, sort, validate) | VM | `filteredAvailableTasks(query)` |

### Write-port exception

`MutableStateFlow<String>` used as a **write-port** is allowed in Composable:

```kotlin
val queryFlow = remember { MutableStateFlow("") }
// Composable writes to it
OutlinedTextField(value = queryFlow.collectAsState().value, onValueChange = { queryFlow.value = it })
// VM reads from it
val results: StateFlow<List<LinkResult>> = queryFlow
    .debounce(300.ms)
    .distinctUntilChanged()
    .flatMapLatest { searchUseCase(it, userId) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

This is a **communication channel**, not a mirror of VM state. The Composable produces, the VM consumes. The VM is still the sole source of truth for `results`.

### Undo: `StateFlow`, not `SharedFlow`

Per `2026-09-08-task-restore-undo`: `_recentlyDeleted` must be a `MutableStateFlow<TaskUi?>`, not a `SharedFlow`. A `SharedFlow` event can be missed on recomposition — the user would never see the undo snackbar. A `StateFlow` always has the latest value:

```kotlin
// ✅ CORRECT — StateFlow, not SharedFlow
private val _recentlyDeleted = MutableStateFlow<TaskUi?>(null)
val recentlyDeleted: StateFlow<TaskUi?> = _recentlyDeleted.asStateFlow()

// ❌ WRONG — SharedFlow event can be missed
private val _undoEvent = MutableSharedFlow<TaskUi>(extraBufferCapacity = 1)
```

---

## Debounced Edits — Silent Save Pattern

For inline-editable fields (title, description), the debounced save must **not** emit a `Saved` event or pulse. The user is still typing — surfacing a "Saved" indicator on every keystroke is annoying UX and floods the event channel.

### The correct pattern

```kotlin
// ViewModel
private val _lastEditedAt = MutableStateFlow<Instant?>(null)
val lastEditedAt: StateFlow<Instant?> = _lastEditedAt.asStateFlow()

private var debounceJob: Job? = null

fun onTitleChange(id: TaskId, draft: String) {
    debounceJob?.cancel()
    debounceJob = viewModelScope.launch {
        delay(300)  // debounce
        repo.updateTitle(id, draft).getOrThrow()
        _lastEditedAt.value = clock.now()  // SILENT — no event emitted
    }
}
```

```kotlin
// Screen — top bar shows continuous "Saved X ago" text
val lastEditedAt by vm.lastEditedAt.collectAsStateWithLifecycle()

TopAppBar(
    title = {
        Row(verticalAlignment = CenterVertically) {
            Text(state.title)
            lastEditedAt?.let { time ->
                Text(
                    formatSavedRelative(clock.now(), time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
)
```

### When to emit `Saved` / pulse events

Only emit `SavedUiEvent` or `SavedPulse` when:
1. The user performs an **explicit action** (taps checkbox, confirms date picker, applies AI suggestion)
2. A **bulk operation** completes (archive all, delete selected)
3. A **navigation-triggered save** occurs (user taps Back and unsaved changes auto-flush)

### The anti-pattern (Regression 5 in `singularity-todo-task-detail-ux`)

```kotlin
// ❌ WRONG — emits Saved on every debounced keystroke
debouncedTitle
    .debounce(300)
    .onEach { repo.updateTitle(it).getOrThrow() }
    .onEach { _events.emit(TaskDetailUiEvent.Saved("Title updated")) }  // SPAM!
    .launchIn(viewModelScope)

// ✅ CORRECT — silent, no event
debouncedTitle
    .debounce(300)
    .onEach {
        repo.updateTitle(it).getOrThrow()
        _lastEditedAt.value = clock.now()  // silent continuous state
    }
    .launchIn(viewModelScope)
```

See `singularity-todo-inline-edit-saved-feedback` skill for the full worked example.

---

## `.first()` Snapshot vs Continuous `.collect()` for Lists in Compose

When a screen loads a list of items (projects, tasks, notes), two patterns exist: **snapshot** (load once with `.first()`) and **continuous** (collect the Flow). Use the right one.

### The snapshot pattern (❌ anti-pattern)

```kotlin
@Composable
fun ProjectPickerSheet(...) {
    val projectsRepo: ProjectsRepository = koinInject()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    LaunchedEffect(Unit) {
        val userId = settingsRepo.userId.first()       // snapshot of userId
        projects = projectsRepo.watchProjects(userId).first()  // snapshot of list
    }
}
```

**Problems:**
- `settingsRepo.userId` is a `Flow<UserId>` — taking `.first()` loses reactivity to profile switches
- `projectsRepo.watchProjects(userId)` is a reactive `Flow<List<Project>>` — taking `.first()` means new projects created while the sheet is open are NOT shown
- If user creates a project in another screen while this picker is open, they must close and reopen to see it

### The continuous pattern (✅ correct)

```kotlin
@Composable
fun ProjectPickerSheet(...) {
    val projectsRepo: ProjectsRepository = koinInject()
    val currentUser: ProfileAwareCurrentUser = koinInject()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }

    val userId by currentUser.scopedUserId.collectAsStateWithLifecycle()
    LaunchedEffect(userId) {
        projectsRepo.watchProjects(userId.value).collect { projects = it }
    }
}
```

**Why this is correct:**
- `currentUser.scopedUserId` is a `StateFlow<UserId>` — collecting it with `collectAsStateWithLifecycle()` re-triggers when the profile switches
- `projectsRepo.watchProjects(userId)` is collected continuously — any new project appears automatically without reopening
- The `LaunchedEffect(userId)` key ensures we re-collect when the user changes

### Decision tree

```
Is the data source a Flow?
  → YES → Is the value needed once (e.g. export, print, one-shot action)?
      → YES → `.first()` is fine
      → NO  → Use `collectAsStateWithLifecycle()` + `LaunchedEffect(key)` for re-collection on parameter changes
  → NO (it's a suspend function or blocking call) → `.first()` or explicit suspend call is appropriate
```

### Inline create — don't re-snapshot after writing

```kotlin
// ❌ WRONG — unnecessary re-fetch after create
scope.launch {
    projectsRepo.create(newProject)
    projects = projectsRepo.watchProjects(userId).first()  // re-fetches everything
}

// ✅ CORRECT — the Flow already emits the new list after create
scope.launch {
    projectsRepo.create(newProject)
    newProjectName = ""
    isCreating = false
    // No re-fetch needed — the Flow from collect {} will receive the update automatically
}
```

Room's reactive `Flow` emits a new value automatically when the backing data changes. After `projectsRepo.create()`, the `collect` block receives the updated list — no manual re-fetch needed.

### Profile switch — re-collect with `LaunchedEffect(key)`

When using `ProfileAwareCurrentUser`, always key `LaunchedEffect` on the `userId`:

```kotlin
val userId by currentUser.scopedUserId.collectAsStateWithLifecycle()
LaunchedEffect(userId) {
    projectsRepo.watchProjects(userId.value).collect { projects = it }
}
```

Without the key, switching profiles would not restart the collection — the new user's projects would never load.

### Anti-pattern: `LaunchedEffect(Unit)` with `collect {}`

```kotlin
// ❌ WRONG — collects forever, never restarts
LaunchedEffect(Unit) {
    repo.watchProjects(userId).collect { projects = it }
}

// ✅ CORRECT — key on the actual dependency
LaunchedEffect(userId) {
    repo.watchProjects(userId.value).collect { projects = it }
}
```

The `Unit` key means "never restart". If the underlying data source changes, the collector stays on the old data. Always key `LaunchedEffect` on the minimum set of parameters that, when changed, require a fresh collection.

---

## Mirror-state Anti-patterns

These are the most common UDF violations found in the codebase. Each example shows the **wrong** pattern (❌) and the **correct** replacement (✅).

### TextField draft mirroring (`TaskDetailViewContent.kt:62-71`)

```kotlin
// ❌ WRONG — mirror-state: Composable owns a copy of the title
var titleDraft by remember { mutableStateOf(ui.task.title) }
var descriptionDraft by remember { mutableStateOf(ui.task.description) }
LaunchedEffect(ui.task.title) { titleDraft = ui.task.title }
LaunchedEffect(ui.task.description) { descriptionDraft = ui.task.description }
TextField(value = titleDraft, onValueChange = { titleDraft = it })

// ✅ CORRECT — VM owns _draftTitle; Composable reads from state
TextField(
    value = ui.draftTitle,
    onValueChange = { vm.onIntent(Domain.UpdateTitle(it)) }
)
```

In the VM:
```kotlin
private val _draftTitle = MutableStateFlow("")
val draftTitle: StateFlow<String> = _draftTitle.asStateFlow()

// _draftTitle lives in the same combine as _latest<Task>:
val state: StateFlow<TaskDetailUiState> = combine(
    _latest, _draftTitle, _draftDescription, ...
) { latest, draftTitle, draftDesc, ... ->
    TaskDetailUiState(latest, draftTitle, draftDesc, ...)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState.Loading)

// Debounced silent save — no event, no pulse
fun onIntent(intent: Domain) {
    when (intent) {
        is Domain.UpdateTitle -> {
            _draftTitle.value = intent.text
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(300)
                repo.updateTitle(intent.taskId, intent.text).getOrThrow()
            }
        }
    }
}
```

### Repository call from inside a Composable

```kotlin
// ❌ WRONG — repo called directly from Composable
val linkRepo: InternalLinkRepository = koinInject()
InternalLinkPickerSheet(
    onSearch = { q ->
        val notes = linkRepo.searchNotes(userId, q)
            .map { LinkResult(it.id.value, it.title, LinkKind.Note) }
        val tasks = linkRepo.searchTasks(q)
            .map { LinkResult(it.id.value, it.title, LinkKind.Task) }
        notes + tasks
    },
    ...
)

// ✅ CORRECT — VM exposes a search method; Composable calls it as a suspend lambda
val vm: NoteEditor = koinViewModel()
InternalLinkPickerSheet(
    onSearch = { q -> vm.searchNotesForLink(q).first() + vm.searchTasksForLink(q).first() },
    ...
)
```

In the VM:
```kotlin
fun searchNotesForLink(query: String): Flow<List<LinkResult>> =
    internalLinkRepo.searchNotes(currentUser.scopedUserId.value, query)
        .map { notes -> notes.map { LinkResult(it.id.value, it.title, LinkKind.Note) } }

fun searchTasksForLink(query: String): Flow<List<LinkResult>> =
    internalLinkRepo.searchTasks(query)
        .map { tasks -> tasks.map { LinkResult(it.id.value, it.title, LinkKind.Task) } }
```

### Business logic in `remember`

```kotlin
// ❌ WRONG — filtering is business logic in a Composable
val filtered = remember(tasks, query) {
    if (query.isBlank()) tasks.take(10)
    else tasks.filter { it.title.contains(query, ignoreCase = true) }.take(10)
}

// ✅ CORRECT — VM exposes a filtered StateFlow
val filteredTasks by vm.filteredAvailableTasks(queryFlow).collectAsStateWithLifecycle()
```

In the VM:
```kotlin
private val _queryForAddTask = MutableStateFlow("")
val filteredAvailableTasks: (StateFlow<String>) -> StateFlow<List<Task>> = { queryFlow ->
    combine(availableTasksFlow, queryFlow) { tasks, query ->
        if (query.isBlank()) tasks.take(10)
        else tasks.filter { it.title.contains(query, ignoreCase = true) }.take(10)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

### Undo state in Screen instead of VM

```kotlin
// ❌ WRONG — undo state held in Composable
var lastDeleted by remember { mutableStateOf<TaskUi?>(null) }
val snackbarJob by remember { mutableStateOf<Job?>(null) }
scope.launch {
    snackbarJob = snackbarHostState.showSnackbar("Task deleted", "Undo")
    if (result == ActionPerformed) vm.restore(task.id)
}

// ✅ CORRECT — VM owns recentlyDeleted as StateFlow; Screen subscribes
val recentlyDeleted by vm.recentlyDeleted.collectAsStateWithLifecycle()
LaunchedEffect(Unit) {
    vm.recentlyDeleted.collect { task ->
        if (task != null) {
            val result = snackbarHostState.showSnackbar("Task deleted", "Undo")
            if (result == ActionPerformed) vm.restore(task.id)
        }
    }
}
```

See `docs/decisions/2026-09-15-viewmodel-state-ownership.md` for the full anti-pattern catalog with references to the specific files and line numbers.
