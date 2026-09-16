---
name: singularity-todo-feature-scaffold
description: Feature scaffold pattern for the Singularity Todo KMP app. Use when adding a new CRUD feature (tasks, notes, projects, tags, reminders, etc.) or when extending an existing feature with a sub-repository (e.g. NoteTagRepository). Documents the 7-file template (Ids.kt, *Domain.kt, *Repository.kt, *UseCase.kt, *ViewModel.kt, *Screen.kt), DI registration in Modules.kt, navigation in AppDestination, and co-located Fake + ViewModel test. Follows the canonical flow: pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (StateFlow + sealed Intent) → Screen. Also covers the Subinterface pattern for feature extensions.
---

# Feature Scaffold — Adding a New CRUD Feature

## Two layouts: flat vs layered

Pick based on feature size.

### Flat layout — small feature (<10 files, 1 screen)

Use when the feature fits in one screen and has no sub-entities or sub-UseCases.

```
feature/<feature>/
├── Ids.kt                  — @JvmInline value class IDs + domain model
├── <Feature>Domain.kt      — pure domain logic, validation (optional)
├── <Feature>Repository.kt  — interface: suspend CRUD + Flow reads
├── <Feature>RepositoryImpl.kt — Room implementation
├── <Feature>UseCase.kt     — ONLY real logic (validation, clock.now(), build)
├── <Feature>ViewModel.kt   — StateFlow<SealedUiState>, sealed Intent
└── <Feature>Screen.kt     — Compose UI
```

### Layered layout — large feature (≥10 files, multiple screens, sub-entities)

Use when the feature has **≥2 screens** (list + detail, list + editor, etc.) **OR sub-entities** (tags, checklist, attachments):

```
feature/<feature>/
├── domain/                           — pure, no platform, no Android imports
│   ├── model/
│   │   ├── <Feature>.kt              — IDs, domain model, CreateXInput, sealed UiState, sealed Intent
│   │   ├── <Feature>List.kt          — list-specific types (if ≥2 screens)
│   │   └── <Feature>DetailState.kt   — detail-specific read model (if 2nd screen)
│   ├── port/
│   │   └── <Feature>Repository.kt    — interface only
│   ├── usecase/
│   │   ├── Create<Feature>.kt        — Create<Feature>UseCase
│   │   ├── Update<Feature>.kt        — Update<Feature>UseCase
│   │   └── <Feature>Mutations.kt   — bulk ops if any
│   └── <Feature>Domain.kt            — pure validation/build, `object`
│
├── data/
│   └── <Feature>RepositoryImpl.kt    — Room impl + toEntity/toDomain mappers
│
└── presentation/
    ├── viewmodel/
    │   ├── <Feature>List.kt          — list VM
    │   └── <Feature>Detail.kt        — detail VM (each screen = its own file)
    ├── screen/
    │   ├── <Feature>List.kt          — list screen
    │   └── <Feature>Detail.kt        — detail screen
    ├── components/                   — only if feature has its own UI widgets
    └── sections/                     — only if screens split into sections
```

**When to upgrade flat → layered:**
- Adding a 2nd screen (detail, editor)
- Adding sub-entities (tags, checklist, attachments)
- `*ViewModel.kt` > 300 lines
- Multiple VMs need shared types

### Naming convention

File name = entity or concept, no role prefix when parent folder already implies the role.

| Path | File name | Class inside |
|---|---|---|
| `domain/model/Task.kt` | `Task` | `Task`, `TaskId`, `UserId`, `TaskPriority`, `TaskKind`, `TaskFilter`, `Task`, `CreateTaskInput` |
| `domain/model/TaskList.kt` | `TaskList` | `TaskGroup`, `TasksUiState`, `AiActionResult`, `FromToday` |
| `domain/model/TaskDetailState.kt` | `TaskDetailState` | `TaskDetailUi`, `TaskDetailUiState`, `TaskDetailIntent` |
| `domain/model/TaskEditorState.kt` | `TaskEditorState` | `TaskEditorUiState`, `TaskEditorIntent`, `reduce()` |
| `presentation/viewmodel/TaskList.kt` | `TaskList` | `TasksViewModel` |
| `presentation/screen/TaskList.kt` | `TaskList` | `TasksScreen` |
| `domain/usecase/CreateTask.kt` | `CreateTask` | `CreateTaskUseCase` |
| `domain/usecase/TaskMutations.kt` | `TaskMutations` | `TaskMutationsUseCase` |

**State-bearers carry `*State` suffix** when in `domain/model/` because the folder is generic. Inside `presentation/viewmodel/` or `presentation/screen/` the role is already implied — no suffix needed.

## Canonical flow

```
pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (StateFlow + sealed Intent) → Screen
```

> **Pass-through use cases are anti-pattern.** `Get<Feature>UseCase`, `Delete<Feature>UseCase`, `ToggleCompleteUseCase` that just delegate to `repo.X()` are boilerplate. Inject the repository directly into the ViewModel.

## Canonical flow for agenda/filter views (AgendaEngine pattern)

When the feature is a **view over existing data** (not a CRUD entity), use the AgendaEngine DSL pattern:

```
pure data DSL (AgendaDefinition) → pure evaluator (AgendaEvaluator.evaluate) → ViewModel (StateFlow<AgendaUiState>) → Screen
```

The DSL builds a declarative `AgendaDefinition` (sections + selectors). The evaluator is a **pure function** that matches tasks against selectors. No repository mutation, no use cases needed — the ViewModel watches all tasks via `TaskFilter.All` and evaluates in-process.

```
feature/agenda/
├── domain/
│   ├── model/
│   │   ├── AgendaDefinition.kt   — Section, AgendaLayout, AgendaLayout
│   │   ├── Selector.kt           — sealed interface with 14 variants
│   │   ├── AgendaUiState.kt      — Loaded/Loading/Error
│   │   ├── AgendaIntent.kt       — sealed user intent
│   │   └── AgendaUiEvent.kt     — one-shot events
│   ├── logic/
│   │   ├── RelativeBucket.kt    — Today/ThisWeek/Overdue/NoDate date math
│   │   ├── AgendaPresets.kt     — Inbox, Today, Upcoming, byProject, byTag
│   │   └── AgendaEvaluator.kt    — pure evaluate() + matches()
│   └── di/
│       └── AgendaDiModule.kt     — viewModel { (definition) -> AgendaViewModel }
├── presentation/
│   ├── viewmodel/
│   │   ├── AgendaDeps.kt        — deps (taskRepo, currentUser, logger)
│   │   └── AgendaViewModel.kt   — state: StateFlow<AgendaUiState>, events: SharedFlow
│   ├── screen/
│   │   ├── AgendaScreen.kt      — @Composable AgendaScreen(definition, ...)
│   │   └── AgendaContent.kt     — LazyColumn with section headers + task rows
│   └── nav/
│       ├── AgendaNavGraph.kt    — expect
│       ├── AgendaNavGraph.android.kt
│       └── AgendaNavGraph.jvm.kt
└── AgendaStartRoute.kt           — in feature/nav/, not feature/agenda/presentation/
```

**When to use AgendaEngine vs standard CRUD:**
| Feature type | Pattern |
|---|---|
| CRUD entity (Task, Note, Project) | Repository + ViewModel |
| View/filter over existing data (agenda, calendar) | DSL + pure evaluator |
| Multiple similar screens with different filters | AgendaEngine (single screen, configurable definition) |

## 1. Ids.kt — Typed ID wrappers + domain model

```kotlin
package com.singularity.todo.feature.<feature>.domain.model

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

## 2. <Feature>Repository.kt — Interface (in `domain/port/`)

```kotlin
package com.singularity.todo.feature.<feature>.domain.port

interface <Feature>Repository {
    fun watchAll(userId: UserId): Flow<List<<Feature>>>
    fun watch(id: <Feature>Id): Flow<<Feature>?>
    suspend fun create(<Feature>: <Feature>): Result<Unit>
    suspend fun update(<Feature>: <Feature>): Result<Unit>
    suspend fun delete(id: <Feature>Id): Result<Unit>
}
```

**Rules:**
- All mutators return `Result<Unit>` / `Result<T>`.
- All mutators are `suspend`.
- Reads return `Flow<T>` (never `List<T>` — so the UI updates reactively).
- No `*Blocking()` methods.

## 3. Use cases — ONLY real logic

**Keep only these use cases** (if they add real value beyond delegation):

```kotlin
class Create<Feature>UseCase(private val repo: <Feature>Repository, private val clock: Clock) {
    suspend operator fun invoke(input: Create<Feature>Input): Result<<Feature>Id> = runCatchingResult {
        val now = clock.now()
        val <feature> = <Feature>(id = <Feature>Id.generate(), ..., createdAt = now, updatedAt = now)
        repo.create(<feature>).getOrThrow()
        <feature>.id
    }
}
```

**DELETE these** (pure pass-through, not use cases):
- `Get<Feature>UseCase` → VM injects `Repo` directly
- `Delete<Feature>UseCase` → VM calls `repo.delete(id).getOrThrow()`
- `Update<Feature>UseCase` → VM calls `repo.update(entity).getOrThrow()`

## 4. ViewModel — State + Intent

```kotlin
sealed interface <Feature>UiState {
    data object Loading : <Feature>UiState
    data class Content(val items: List<<Feature>>) : <Feature>UiState
    data class Error(val message: String) : <Feature>UiState
}

sealed interface <Feature>Intent {
    data class Delete(val id: <Feature>Id) : <Feature>Intent
    data class Update(val <feature>: <Feature>) : <Feature>Intent
}

class <Feature>ViewModel(
    private val repo: <Feature>Repository,
    private val create<Feature>: Create<Feature>UseCase,
    settings: SettingsRepository,  // for userId
) : ViewModel() {

    private val userId = settings.userId.map { UserId.fromString(it) }

    val state: StateFlow<<Feature>UiState> = userId
        .flatMapLatest { repo.watchAll(it) }
        .map { <Feature>UiState.Content(it) }
        .catch { emit(<Feature>UiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), <Feature>UiState.Loading)

    fun processIntent(intent: <Feature>Intent) = viewModelScope.launch {
        when (intent) {
            is <Feature>Intent.Delete -> repo.delete(intent.id).getOrThrow()
            is <Feature>Intent.Update -> repo.update(intent.<feature>).getOrThrow()
        }
    }
}
```

## 5. Screen — Compose UI

```kotlin
@Composable
fun <Feature>Screen(
    viewModel: <Feature>ViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()  // ← lifecycle-aware
    when (val s = state) {
        is <Feature>UiState.Loading -> LoadingIndicator()
        is <Feature>UiState.Content -> <Feature>List(items = s.items)
        is <Feature>UiState.Error -> ErrorMessage(s.message)
    }
}
```

**⚠️ Always use `collectAsStateWithLifecycle()`** — not `collectAsState()`. Lifecycle-aware collection stops subscribing when Activity is in background, saves CPU.

## DI Registration (Modules.kt — `core/di/`)

```kotlin
// Modules.kt
import com.singularity.todo.feature.<feature>.domain.model.*
import com.singularity.todo.feature.<feature>.domain.port.*
import com.singularity.todo.feature.<feature>.domain.usecase.*
import com.singularity.todo.feature.<feature>.data.*
import com.singularity.todo.feature.<feature>.presentation.viewmodel.*

single<<Feature>Repository> { <Feature>RepositoryImpl(get(), get()) }  // interface from domain, impl from data
factory { Create<Feature>UseCase(get(), get()) }
viewModelOf(::FeatureViewModel)
```

See `singularity-todo-koin-di` skill for full DI conventions.

## Navigation (AppDestination.kt + Nested Graph)

This project uses **two-level Nav3 navigation**: a top-level `Nav3State` with `NavBackStack<AppDestination>` for tabs, and **nested graphs** per feature with their own `NavBackStack<FeatureRoute>`.

### Top-level: `AppDestination` in `feature/nav/AppDestination.kt`

```kotlin
sealed interface AppDestination : NavKey {
    val title: String
    val icon: ImageVector

    @Serializable data object Inbox : AppDestination { ... }
    @Serializable data object Today : AppDestination { ... }

    // AgendaEngine: singleton graph, 3 start routes
    @Serializable data class AgendaGraph(
        val start: AgendaStartRoute = AgendaStartRoute.Inbox,
    ) : AppDestination { override val title = "Agenda" }
}
```

### Start routes vs Graph routes

There are **two separate sealed hierarchies** for each feature:

1. **`*StartRoute`** — what the outer AppNavHost uses to mount the graph at a specific start position
2. **`*Route`** (inner) — the full route hierarchy inside the nested graph

For Agenda: `AgendaStartRoute` lives in `feature/nav/` (not `feature/agenda/presentation/nav/`), while `AgendaNavGraph` lives in `feature/agenda/presentation/nav/`.

```kotlin
// feature/nav/AgendaStartRoute.kt — OUTER graph mount point
@Serializable
sealed interface AgendaStartRoute : NavKey {
    @Serializable data object Inbox : AgendaStartRoute
    @Serializable data object Today : AgendaStartRoute
    @Serializable data object Upcoming : AgendaStartRoute
    @Serializable data class Project(val projectId: String) : AgendaStartRoute {
        val id: ProjectId get() = ProjectId.fromString(projectId)
    }
    @Serializable data class Tag(val tagId: String) : AgendaStartRoute {
        val id: TagId get() = TagId.fromString(tagId)
    }
}
```

**⚠️ `@Serializable` required on `TagId` and `ProjectId` when used in routes:** If a route embeds a typed ID as a String field (like `Tag(val tagId: String)`), the ID class itself must be `@Serializable`. Add `@Serializable` to `TagId` and `ProjectId` in `feature/tags/Ids.kt` and `feature/projects/Ids.kt`.

### Feature nested graph: `*Route.kt` + `*NavGraph.kt` (expect/actual)

Each feature has its own sealed `Route` hierarchy and `*NavGraph` expect/actual:

```
feature/<feature>/presentation/nav/
├── <Feature>Route.kt      — sealed interface + data objects
├── <Feature>NavGraph.kt   — expect fun <Feature>NavGraph(...)  ← commonMain
├── <Feature>NavGraph.android.kt  — actual: navSavedStateConfig + rememberNavBackStack
└── <Feature>NavGraph.jvm.kt      — actual: rememberInMemoryNavBackStack
```

**Never** use `AppDestination.<Feature>Detail(id)` for inner-screen navigation — use the feature's own `*Route` inside the nested graph. `AppDestination` variants are only for top-level tab switching.

### When to add a new nested graph

1. Create `feature/<feature>/presentation/nav/<Feature>Route.kt`:
   ```kotlin
   @Serializable
   sealed interface <Feature>Route : NavKey {
       @Serializable data object List : <Feature>Route
       @Serializable data class Detail(val id: <Feature>Id) : <Feature>Route
   }
   ```
2. Create `feature/<feature>/presentation/nav/<Feature>NavGraph.kt` (expect)
3. Create `*NavGraph.android.kt` + `*NavGraph.jvm.kt` (actual implementations)
4. Add `AppDestination.<Feature>Graph` to `AppDestination` sealed interface
5. Wire in `AppNavHost` using `entryProvider`

**See `singularity-todo-nav3-nested-graphs`** for full nested graph architecture details.

## Subinterface Pattern — when to split a Repository

When a feature grows to encompass multiple bounded contexts, split the repository into sub-interfaces registered separately in DI.

**Example: NoteTagRepository**

Notes and tags have a many-to-many relationship. Instead of bloating `NotesRepository` with tag-specific methods, create a dedicated sub-interface:

```kotlin
interface NoteTagRepository {
    fun watchTags(noteId: NoteId): Flow<List<Tag>>
    fun watchNotesForTag(tagId: TagId): Flow<List<Note>>
    suspend fun setTags(noteId: NoteId, tagIds: Set<TagId>): Result<Unit>
}

interface NotesRepository {
    fun watchAll(userId: UserId): Flow<List<Note>>
    fun watchNote(id: NoteId): Flow<Note?>
    suspend fun createWithContent(...): Result<NoteId>
    suspend fun updateContent(...): Result<Unit>
    suspend fun softDelete(id: NoteId): Result<Unit>
}
```

## When to use Subinterface vs extending the main Repository

| Situation | Pattern |
|---|---|
| Many-to-many relationship (notes ↔ tags) | Subinterface (`NoteTagRepository`) |
| Plugin-like extensibility (AI tools) | `@IntoSet` on individual tool classes |
| Read vs write concerns (watchAll vs mutate) | Keep in same repo, different method families |
| Different storage backends | Subinterface with different implementations |

## Co-located Tests

```kotlin
class <Feature>ViewModelTest {
    private fun createVm(
        repo: <Feature>Repository = Fake<Feature>Repository(),
        createUseCase: Create<Feature>UseCase = Create<Feature>UseCase(repo, FixedClock()),
    ) = <Feature>ViewModel(repo, createUseCase, FakeSettingsRepository("user-1"))

    @Test fun `deletes item`() = runTest {
        val vm = createVm()
        vm.processIntent(<Feature>Intent.Delete(id))
    }
}
```

## Layer-boundary checklist

Before merging a feature change:

- [ ] `presentation/` does NOT import from `data/` directly (only via `domain/port/` interfaces)
- [ ] `domain/` does NOT import from `data/` or `presentation/`
- [ ] `data/` does NOT import from `presentation/`
- [ ] All cross-feature references go through interface ports in `domain/port/`
- [ ] Use cases live in `domain/usecase/` and depend only on `domain/port/` interfaces
- [ ] ViewModels depend only on `domain/port/` interfaces + `domain/usecase/`
- [ ] `collectAsStateWithLifecycle()` used (not `collectAsState()`)
- [ ] ViewModels contain `scopeOverride` for tests
- [ ] **Run `just lint`** — detekt finds 0 new violations (baseline absorbs existing ones)
- [ ] **Run `just detekt-fix`** — ktlint auto-fixes formatting; review the diff before staging
- [ ] New feature code is covered by existing tests (kover aggregates coverage across commonMain + jvmMain + androidMain automatically)

See `singularity-todo-clean-architecture-audit` for automated checks.
See `singularity-todo-quality-tools` for full lint/coverage commands.

## Anti-patterns to Avoid

1. **`runBlocking` in VM constructor** — use `userIdFlow: Flow<UserId>` + `combine(filter, userId) { ... }`
2. **Pass-through use cases** — `GetX`, `DeleteX`, `ToggleX` that do `repo.X().getOrThrow()`
3. **`require { throw ... }` inside the `require` lambda** — `require` itself throws; the lambda body never runs
4. **`java.io.File` directly** — use the `FileSystem` port
5. **MockK / Mockito** — use `Fake<Feature>Repository()`
6. **Adding tag/attachment operations to the main repository** — use subinterface instead
7. **UI layer imports data layer** — the `presentation/` only sees `domain/port/` interfaces, never `data/` implementations
8. **`collectAsState()` instead of `collectAsStateWithLifecycle()`** — wastes CPU when backgrounded

---

## When NOT to Extract a Shared Layer

Extracting shared infrastructure too early creates **leaky abstractions** and **over-engineering**. Follow the **Rule of Three**: abstract only when ≥3 features share the same pattern.

### Don't extract: `BaseEntity` marker interface

```kotlin
// ❌ Simple boundary class — shares nothing meaningful
interface BaseEntity<ID> { val id: ID; val createdAt: Instant; val updatedAt: Instant }
```

**Do instead:** Keep `Task` and `Project` as independent data classes. Use `InMemoryStore<E>` (composition utility) for test fakes.

### Don't extract: `FakeStoreRepository<E>` abstract class

```kotlin
// ❌ Simple boundary class — each repo overrides everything anyway
abstract class FakeStoreRepository<E : BaseEntity<*>> { ... }
```

**Do instead:** Use `InMemoryStore<E>` as a **composition helper**:
```kotlin
class FakeTaskRepository : TaskRepository {
    private val store = InMemoryStore<Task>(keyOf = { it.id.value })
}
```

### When shared infrastructure IS justified

Only extract when **all three** are true:
1. **≥3 features** use the same pattern.
2. The abstraction has **behaviour**, not just shared fields.
3. The abstraction's contract is **stable**.

**Examples that passed the test:**
- `Either<AppError.Validation, T>` + `toResult()` — used in all CreateUseCases
- `InMemoryStore<E : Any>` — utility for in-memory CRUD in all Fake repositories
- `ProfileAwareCurrentUser` — injected into all write operations

**Examples that were rejected:**
- `BaseEntity` marker interface — no behaviour
- Generic `CreateUseCase<E, Input>` — each feature has different validation logic

---

## 1-Level Hierarchy Invariant

Projects support a **1-level parent hierarchy**: a project may have a `parentId` pointing to another project, but that parent **must not have its own parent** (`parent.parentId == null`). This is a deliberate simplification.

```kotlin
// Valid
val work = Project(id, name = "Work", parentId = null, ...)
val client = Project(id, name = "Client A", parentId = work.id, ...)

// NOT valid — structurally impossible
val invalid = Project(name = "Task", parentId = workClient.id, ...) // workClient already has parentId
```

Domain enforcement:
```kotlin
if (input.parentId != null) {
    val parent = repo.findById(input.parentId).getOrNull()
        ?: return Result.failure(AppError.Validation("Parent project not found"))
    check(parent.parentId == null) {
        AppError.Validation("Only root projects can be parents.")
    }
}
```
