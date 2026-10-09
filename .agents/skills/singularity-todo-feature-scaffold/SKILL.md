---
name: singularity-todo-feature-scaffold
description: 'Feature scaffold pattern for the Singularity Todo KMP app. Use when adding a new CRUD feature (tasks, notes, projects, tags, reminders) or extending an existing feature with a sub-repository (e.g. NoteTagRepository). Documents the 7-file template, DI registration, navigation, and testing. Canonical flow: pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (MviViewModel + sealed Intent) → Screen.'
---

# Feature Scaffold — Adding a New CRUD Feature

## When to use

Adding a **new CRUD feature** (tasks, notes, projects, tags, reminders) or **extending an existing feature** with a sub-repository (e.g. `NoteTagRepository`).

## Two layouts: flat vs layered

### Flat layout — small feature (<10 files, 1 screen)

```
feature/<feature>/
├── Ids.kt                  — @JvmInline value class IDs + domain model
├── <Feature>Domain.kt      — pure domain logic, validation
├── <Feature>Repository.kt  — interface: suspend CRUD + Flow reads
├── <Feature>RepositoryImpl.kt — Room implementation
├── <Feature>UseCase.kt     — ONLY real logic (validation, clock.now(), build)
├── <Feature>ViewModel.kt   — StateFlow<SealedUiState>, sealed Intent
└── <Feature>Screen.kt     — Compose UI
```

### Layered layout — large feature (≥10 files, multiple screens, sub-entities)

```
feature/<feature>/
├── domain/
│   ├── model/<Feature>.kt, <Feature>List.kt, <Feature>DetailState.kt
│   ├── port/<Feature>Repository.kt
│   ├── usecase/Create<Feature>.kt, Update<Feature>.kt, <Feature>Mutations.kt
│   ├── logic/<Feature>Calculator.kt, <Feature>Parser.kt, Computed.kt
│   └── <Feature>Domain.kt
├── data/<Feature>RepositoryImpl.kt
└── presentation/
    ├── viewmodel/<Feature>List.kt, <Feature>Detail.kt
    ├── screen/<Feature>List.kt, <Feature>Detail.kt
    └── components/, sections/
```

**Upgrade flat → layered when:** 2nd screen, sub-entities, `*ViewModel.kt` > 300 lines, or shared types.

### Naming convention

File name = entity/concept, no role prefix when folder implies role. **State-bearers get `*State` suffix** in `domain/model/` (folder is generic); no suffix in `presentation/viewmodel/` or `presentation/screen/`.

| Path | File name | Classes |
|---|---|---|
| `domain/model/Task.kt` | `Task` | `Task`, `TaskId`, `CreateTaskInput` |
| `domain/model/TaskList.kt` | `TaskList` | `TaskGroup`, `TasksUiState` |
| `domain/usecase/CreateTask.kt` | `CreateTask` | `CreateTaskUseCase` |

## Canonical flow

```
pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (StateFlow + sealed Intent) → Screen
```

> **Pass-through use cases are anti-pattern.** `Get<Feature>UseCase`, `Delete<Feature>UseCase` that delegate to `repo.X()` are boilerplate. Inject the repository directly into the ViewModel.

## domain/logic/ — pure functions

| `domain/logic/` | `domain/usecase/` |
|---|---|
| No side effects, no `Clock.now()` | May call repository (I/O) |
| Deterministic, testable in `commonTest` | Coordinates multiple operations |

**Pattern guide:** `singularity-todo-domain-logic-pattern`.

## AgendaEngine pattern (views over existing data)

**View/filter over existing data** (agenda, calendar), not CRUD entity:

```
pure data DSL (AgendaDefinition) → pure evaluator (AgendaEvaluator.evaluate) → ViewModel → Screen
```

| Feature type | Pattern |
|---|---|
| CRUD entity (Task, Note, Project) | Repository + ViewModel |
| View/filter over existing data | DSL + pure evaluator |

## 7-file template

### 1. Ids.kt — Typed ID wrappers + domain model

```kotlin
@JvmInline
value class <Feature>Id private constructor(val value: String) {
    companion object {
        fun fromString(s: String): <Feature>Id = <Feature>Id(s)
        fun generate(): <Feature>Id = <Feature>Id(nextId())
    }
}

data class <Feature>(
    val id: <Feature>Id,
    val userId: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    // ... feature-specific fields
)
```

### 2. <Feature>Repository.kt — Interface (in `domain/port/`)

```kotlin
interface <Feature>Repository {
    fun watchAll(userId: UserId): Flow<List<<Feature>>>
    fun watch(id: <Feature>Id): Flow<<Feature>?>
    suspend fun create(<Feature>: <Feature>): Result<Unit>
    suspend fun update(<Feature>: <Feature>): Result<Unit>
    suspend fun delete(id: <Feature>Id): Result<Unit>
}
```

**Rules:** Mutators return `Result<T>` and are `suspend`. Reads return `Flow<T>`. No `*Blocking()`.

### 3. <Feature>Domain.kt — Pure domain logic

```kotlin
object <Feature>Domain {
    fun validate(input: Create<Feature>Input): Result<Unit> = runCatching {
        require(input.name.isNotBlank()) { "Name is blank" }
    }
    fun build(input: Create<Feature>Input, clock: Clock, idGenerator: () -> <Feature>Id): <Feature> {
        val now = clock.now()
        return <Feature>(id = idGenerator(), ..., createdAt = now, updatedAt = now)
    }
}
```

### 4. <Feature>RepositoryImpl.kt — Room

```kotlin
class <Feature>RepositoryImpl(
    private val dao: <Feature>Dao,
    private val mapper: <Feature>Mapper,
) : <Feature>Repository {
    override fun watchAll(userId: UserId) = dao.watchAllForUser(userId)
    override suspend fun create(<feature>: <Feature>) = runCatching { dao.insert(mapper.toEntity(<feature>)) }
    // ... other CRUD
}
```

### 5. <Feature>UseCase.kt — ONLY real logic

```kotlin
class Create<Feature>UseCase(private val repo: <Feature>Repository, private val clock: Clock) {
    suspend operator fun invoke(input: Create<Feature>Input): Result<<Feature>Id> = runCatchingResult {
        <Feature>Domain.validate(input).getOrThrow()
        val <feature> = <Feature>Domain.build(input, clock, <Feature>Id::generate)
        repo.create(<feature>).getOrThrow()
        <feature>.id
    }
}
```

**DELETE:** `Get<Feature>UseCase`, `Delete<Feature>UseCase`, `Update<Feature>UseCase` — VM injects `Repo` directly.

### 6. <Feature>ViewModel.kt — MviViewModel base

All VMs extend [MviViewModel](core/ui/MviViewModel.kt). Unifies `MutableStateFlow`, `EventBus`, `onIntent`.

```kotlin
sealed interface <Feature>UiState {
    data object Loading : <Feature>UiState
    data class Content(val items: List<<Feature>>) : <Feature>UiState
    data class Error(val message: String) : <Feature>UiState
}
sealed interface <Feature>Intent : MviIntent {
    data class Delete(val id: <Feature>Id) : <Feature>Intent
    data class Update(val <feature>: <Feature>) : <Feature>Intent
}
sealed interface <Feature>UiEvent : MviEvent {
    data class ShowError(val message: String) : <Feature>UiEvent
}

class <Feature>ViewModel(
    private val repo: <Feature>Repository,
    private val create<Feature>: Create<Feature>UseCase,
    private val settings: SettingsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<<Feature>UiState, <Feature>Intent, <Feature>UiEvent>(
    initialState = <Feature>UiState.Loading, scope = scope,
) {
    init {
        addCloseable(scope)
        scope.launch {
            val userId = UserId.fromString(settings.userId.first())
            repo.watchAll(userId)
                .catch { updateState { <Feature>UiState.Error(it.message ?: "Error") } }
                .collect { items -> updateState { <Feature>UiState.Content(items) } }
        }
    }
    override fun onIntent(intent: <Feature>Intent) {
        when (intent) {
            is <Feature>Intent.Delete -> scope.launch { repo.delete(intent.id).onFailure { emit(<Feature>UiEvent.ShowError(it.message ?: "Error")) } }
            is <Feature>Intent.Update -> scope.launch { repo.update(intent.<feature>).onFailure { emit(<Feature>UiEvent.ShowError(it.message ?: "Error")) } }
        }
    }
}
```

**Exception: pure read-through** — if VM mirrors `repo.watchAll()` only with no draft/branching, `stateIn(WhileSubscribed)` is acceptable.

### 7. <Feature>Screen.kt — Compose UI

```kotlin
@Composable
fun <Feature>Screen(viewModel: <Feature>ViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is <Feature>UiState.Loading -> LoadingIndicator()
        is <Feature>UiState.Content -> <Feature>List(items = s.items)
        is <Feature>UiState.Error -> ErrorMessage(s.message)
    }
}
```

**Always `collectAsStateWithLifecycle()`** — not `collectAsState()`.

## DI Registration

```kotlin
single<<Feature>Repository> { <Feature>RepositoryImpl(get(), get()) }
factory { Create<Feature>UseCase(get(), get()) }
viewModelOf(::FeatureViewModel)
```

See `singularity-todo-koin-dsl`.

## Navigation

Two-level Nav3: top-level `NavBackStack<AppDestination>` for tabs + **nested graphs** per feature.

### AppDestination (top-level)

```kotlin
sealed interface AppDestination : NavKey {
    val title: String; val icon: ImageVector
    @Serializable data object Inbox : AppDestination { ... }
    @Serializable data class AgendaGraph(val start: AgendaStartRoute = AgendaStartRoute.Inbox) : AppDestination
}
```

### Feature nested graph

```
feature/<feature>/presentation/nav/
├── <Feature>Route.kt      — sealed interface + data objects
├── <Feature>NavGraph.kt   — expect (commonMain)
├── <Feature>NavGraph.android.kt  — navSavedStateConfig + rememberNavBackStack
└── <Feature>NavGraph.jvm.kt      — rememberInMemoryNavBackStack
```

**Two hierarchies:** `*StartRoute` (outer mount point, in `feature/nav/`) and `*Route` (inner, inside nested graph).

### Adding a new nested graph

1. `feature/<feature>/presentation/nav/<Feature>Route.kt`:
   ```kotlin
   @Serializable sealed interface <Feature>Route : NavKey {
       @Serializable data object List : <Feature>Route
       @Serializable data class Detail(val id: <Feature>Id) : <Feature>Route
   }
   ```
2. Create `*NavGraph.kt` (expect) + `*NavGraph.android.kt` + `*NavGraph.jvm.kt`
3. Add `AppDestination.<Feature>Graph` to `AppDestination`
4. Wire in `AppNavHost` using `entryProvider`

**See `singularity-todo-nav3-nested-graphs`.** `@Serializable` required on `TagId`/`ProjectId` when used in routes.

## Subinterface Pattern

Split repository into sub-interfaces when feature grows to multiple bounded contexts.

```kotlin
interface NoteTagRepository {
    fun watchTags(noteId: NoteId): Flow<List<Tag>>
    suspend fun setTags(noteId: NoteId, tagIds: Set<TagId>): Result<Unit>
}
interface NotesRepository {
    fun watchAll(userId: UserId): Flow<List<Note>>
    suspend fun createWithContent(...): Result<NoteId>
    // tag methods NOT here
}
```

| Situation | Pattern |
|---|---|
| Many-to-many (notes ↔ tags) | Subinterface (`NoteTagRepository`) |
| Plugin extensibility (AI tools) | `factory { Tool(get(), get()) }` + `single<Set<Tool>> { getAll<Tool>() }` |
| Read vs write concerns | Keep in same repo |
| Different storage backends | Subinterface |

## Testing

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class FooViewModelTest {
    private val fakeRepo = FakeFooRepository()
    private val deps = FooDeps(repo = fakeRepo, currentUser = FakeProfileAwareCurrentUser(), clock = Clock)

    private fun createVm(param: Type, scope: CoroutineScope = this) =
        FooViewModel(deps = deps, param = param, scope = scope)

    @Test fun initialState_isLoading() = runTest {
        val vm = createVm(param, this)
        advanceUntilIdle()
        assertIs<FooUiState.Loading>(vm.state.value)
    }
    @Test fun setName_isDirty() = runTest {
        val vm = createVm(param, this)
        advanceUntilIdle()
        vm.processIntent(FooIntent.SetName("Edited"))
        assertTrue((vm.state.value as? FooUiState.Editing)?.draft?.isDirty == true)
    }
}
```

**Fakes:** `FakeTaskRepository`, `FakeProjectsRepository`, `FakeNotesRepository`, `FakeTagsRepository`, `FakeSettingsRepository`, `FakeProfileAwareCurrentUser`, `FakeReminderRepository`, `FakeAuthRepository`.

See `singularity-todo-test-helpers`.

## Layer-boundary checklist

Before merging:
- [ ] `presentation/` does NOT import from `data/` (only via `domain/port/`)
- [ ] `domain/` does NOT import from `data/` or `presentation/`
- [ ] `data/` does NOT import from `presentation/`
- [ ] Cross-feature refs go through `domain/port/` interfaces
- [ ] Use cases in `domain/usecase/`, depend only on `domain/port/`
- [ ] ViewModels depend only on `domain/port/` + `domain/usecase/`
- [ ] `collectAsStateWithLifecycle()` used
- [ ] ViewModels take `scope: CoroutineScope` in primary constructor
- [ ] Repositories do NOT own private `CoroutineScope`
- [ ] `just lint` — 0 new detekt violations
- [ ] `just detekt-fix` — report violations (auto-fix is broken; fix manually)

## Anti-patterns

1. **`runBlocking` in VM** — use `userIdFlow: Flow<UserId>` + `flatMapLatest`
2. **Pass-through use cases** — `GetX`, `DeleteX` that do `repo.X().getOrThrow()`
3. **`require { throw ... }` in lambda** — `require` itself throws; lambda never runs
4. **`java.io.File` directly** — use `FileSystem` port
5. **MockK / Mockito** — use `Fake<Feature>Repository()`
6. **UI imports data layer** — `presentation/` only sees `domain/port/`
7. **`collectAsState()`** — wastes CPU when backgrounded

## ViewModel testability checklist

If test is hard to write, the VM is wrong.

- [ ] `state` is `MutableStateFlow<X>` — plain, no `.stateIn(...)`
- [ ] `init` uses injected `CoroutineScope` — primary constructor takes `scope`
- [ ] No `combine(...)` — extract to `init { scope.launch { _state.value = computed } }`
- [ ] No side effects in flow operators — never `_state.value = ...` inside `map`, `combine`
- [ ] Editable state in `*State` class (e.g., `DraftState`)
- [ ] Test passes `this` (test scope), NOT `backgroundScope`

If test needs Turbine, `expectMostRecentItem`, `awaitItem` — the VM is wrong.

See `singularity-todo-testable-vm`.

## Repository auth-safety — DAO `*ForUser` pattern

### Mandatory constructor

```kotlin
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,  // ← mandatory
) : ProjectsRepository
```

### DAO mutation template

```kotlin
@Query("UPDATE projects SET parent_id = :parentId, updated_at = :ts WHERE id = :id AND user_id = :userId")
suspend fun setParentForUser(id: String, parentId: String?, ts: Long, userId: String): Int
```

Repository impl enforces ownership:

```kotlin
override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
    val uid = currentUser.scopedUserId.value.value
    val rows = projectDao.setParentForUser(id.value, parentId?.value, updatedAt, uid)
    require(rows > 0) { "Project $id not found or not owned by user" }
}
```

### Fake DAO stub

```kotlin
override suspend fun setParentForUser(id: String, parentId: String?, ts: Long, userId: String): Int =
    mutateForUser(id, userId) { it.copy(parentId = parentId, updatedAt = ts) }
```

### No `watchById(id).first()` for one-shot reads

Use `dao.getById(id)` (direct suspend) instead.

See `singularity-todo-repository-architecture`.

## 1-Level Hierarchy Invariant

Projects support **1-level parent hierarchy**: a project may have `parentId`, but that parent **must not have its own parent**.

```kotlin
// Valid
val work = Project(id, name = "Work", parentId = null, ...)
val client = Project(id, name = "Client A", parentId = work.id, ...)

// NOT valid
val invalid = Project(name = "Task", parentId = workClient.id, ...) // workClient already has parentId
```

Domain enforcement — return `Result.failure(AppError.Validation(...))` for expected validation failures. Use `check { }` only for invariant violations that indicate a bug:

```kotlin
check(parent.parentId == null) { "Only root projects can have children." }
```

**`check`/`require` semantics:** Takes condition + lazy message lambda evaluated only on failure. Common mistake:

```kotlin
// WRONG
require(input.name.isNotBlank()) { throw AppError.Validation("Name is blank") }
// RIGHT
require(input.name.isNotBlank()) { "Name is blank" }
```

## When NOT to Extract a Shared Layer

**Rule of Three:** abstract only when ≥3 features share the same pattern with **behaviour** (not just fields) and a **stable contract**.

**Passed:** `Either<AppError.Validation, T>` + `toResult()`, `InMemoryStore<E>`, `ProfileAwareCurrentUser`.

**Rejected:** `BaseEntity` marker (no behaviour), generic `CreateUseCase<E, Input>` (different validation per feature).

## Related Skills

- `singularity-todo-mvi-framework` — MviViewModel, EventBus, StateStrategy
- `singularity-todo-testable-vm` — Canonical VM test pattern
- `singularity-todo-domain-logic-pattern` — pure functions in `domain/logic/`
- `singularity-todo-repository-architecture` — DAO `*ForUser`, atomic bootstrap
- `singularity-todo-clean-architecture-audit` — Layer-boundary grep checks
- `singularity-todo-coroutine-scopes` — `createBackgroundScope()` placement
- `singularity-todo-sheet-extraction` — extracting inline sheets
