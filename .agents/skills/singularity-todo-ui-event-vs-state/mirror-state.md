# Mirror-state anti-patterns

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
// (This `combine + stateIn(WhileSubscribed)` is the *legitimate* read-through
// shape — single upstream flow, no init-time side effects, no draft mutations on
// intent. For stateful VMs with init, drafts, or intents that mutate local state,
// default to plain `MutableStateFlow` written in `init`; see
// singularity-todo-testable-vm.)
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

---
