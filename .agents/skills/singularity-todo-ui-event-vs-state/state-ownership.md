# State ownership boundary

## State Ownership Boundary

The three categories above are **mutually exclusive**. The test for any piece of state is: "Does this come from a repository or use case? Does more than one Composable on the screen need it? Is it computed from VM state?" — if any answer is yes, it belongs in the VM.

### The ownership table

| Category | Where it lives | Example |
|---|---|---|
| Domain state (lists, filters, current entity, domain-derived values) | VM `StateFlow` | `state.tasks`, `_filter`, `_selectedIds`, `recentlyDeleted` |
| Snapshot from repo `Flow` | VM `StateFlow` via `combine + flatMapLatest` | `projectNamesFlow`, `parentOptionsFlow` |
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
