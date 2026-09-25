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
│   ├── logic/                        — PURE functions: no side effects, no Clock, fully testable in commonTest
│   │   ├── <Feature>Calculator.kt    — pure arithmetic/logic (e.g. nextOccurrence, missedCount)
│   │   ├── <Feature>Parser.kt        — pure string → domain model (e.g. DSL parser)
│   │   ├── <Feature>Validator.kt     — pure validation without side effects
│   │   └── Computed.kt               — pure computed properties (e.g. isBlocked, effectiveTags)
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

## `domain/logic/` — pure functions with no side effects

For complex pure logic that doesn't belong in a `UseCase` (no I/O, no `Clock.now()`), create a file in `domain/logic/`:

```
domain/logic/
├── RecurrenceCalculator.kt   — date arithmetic (pure: nextOccurrence, missedCount)
├── RecurrenceParser.kt        — DSL parsing (pure: String → RecurrenceSpec)
├── DependencyValidatorImpl.kt  — graph validation (pure: assertNoSelfLoop)
└── Computed.kt               — derived state (pure: isBlocked, effectiveTags)
```

**`domain/logic/` vs `domain/usecase/`:**

| `domain/logic/` | `domain/usecase/` |
|---|---|
| No side effects | May call repository (I/O) |
| No `Clock.now()` | Receives `Instant`/`LocalDate` as parameter |
| Deterministic — same input → same output | Coordinates multiple operations |
| Fully testable in `commonTest` | Testable with `FakeRepositories` |
| Examples: parser, calculator, validator | Examples: `CreateTask`, `CompleteRecurringTask` |

**Full pattern guide:** see `singularity-todo-domain-logic-pattern`.

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

**This is the default, canonical shape — extend `MviViewModel`.** MviViewModel provides `state`, `events`, `emit()`, `updateState()`, and the `onIntent()` dispatcher out of the box. See `singularity-todo-mvi-framework` for the full API reference and `singularity-todo-testable-vm` for rationale.

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
    private val settings: SettingsRepository,  // for userId
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<<Feature>UiState, <Feature>Intent, <Feature>UiEvent>(
    initialState = <Feature>UiState.Loading,
    scope = scope,
) {

    init { addCloseable(scope) }

    init {
        scope.launch {
            val userId = UserId.fromString(settings.userId.first())
            repo.watchAll(userId)
                .catch { updateState { <Feature>UiState.Error(it.message ?: "Error") } }
                .collect { items -> updateState { <Feature>UiState.Content(items) } }
        }
    }

    override fun onIntent(intent: <Feature>Intent) {
        when (intent) {
            is <Feature>Intent.Delete -> scope.launch {
                repo.delete(intent.id).getOrThrow()
            }
            is <Feature>Intent.Update -> scope.launch {
                repo.update(intent.<feature>).getOrThrow()
            }
        }
    }
}
```

Test (3 lines per case — no Turbine, no `expectMostRecentItem`):

```kotlin
@Test
fun loadsContent() = runTest {
    val vm = <Feature>ViewModel(fakeRepo, createUseCase, fakeSettings, this)  // `this` = TestScope
    advanceUntilIdle()
    assertIs<<Feature>UiState.Content>(vm.state.value)
}
```

### Exception: pure read-through (opt-in, not the default)

If the VM genuinely has **no draft, no init-time branching, and no intents beyond simple pass-through mutations** — a plain list screen that only ever mirrors `repo.watchAll()` — `stateIn(WhileSubscribed)` is an acceptable alternative. This is the same exception documented in `singularity-todo-testable-vm` (`AgendaViewModel` case). Tests for this variant do need Turbine, and that's an accepted, explicit tradeoff for this narrow case — not a signal to relax the default elsewhere:

```kotlin
class <Feature>ViewModel(
    private val repo: <Feature>Repository,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<<Feature>UiState> = settings.userId
        .map { UserId.fromString(it) }
        .flatMapLatest { repo.watchAll(it) }
        .map { <Feature>UiState.Content(it) }
        .catch { emit(<Feature>UiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), <Feature>UiState.Loading)
}
```

**Default to the `MutableStateFlow` + `init` shape above unless you've confirmed your VM fits the read-through exception.** When in doubt, use the default — it's strictly more testable and costs nothing extra for simple CRUD.

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

## Test Patterns for ViewModels

Tests live alongside source in `jvmTest/` using `Fake*` repositories from `test/fakes/FakeRepositories.kt`. The canonical test shape is documented in `singularity-todo-test-helpers` — the abbreviated summary below.

### Standard test structure

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class FooViewModelTest {

    private val fakeRepo = FakeFooRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser()
    private val deps = FooDeps(repo = fakeRepo, currentUser = fakeCurrentUser, clock = Clock)

    private fun createVm(param: Type, scope: CoroutineScope = this) =
        FooViewModel(deps = deps, param = param, scope = scope)

    // Shape 1: smoke — initial state
    @Test
    fun initialState_isLoading() = runTest {
        val vm = createVm(param, this)
        advanceUntilIdle()
        assertIs<FooUiState.Loading>(vm.state.value)
    }

    // Shape 2: intent → state transition
    @Test
    fun setName_isDirty() = runTest {
        val vm = createVm(param, this)
        advanceUntilIdle()
        vm.onIntent(FooIntent.SetName("Edited"))
        assertTrue((vm.state.value as? FooUiState.Editing)?.draft?.isDirty == true)
    }
}
```

### Smoke tests for new VMs

Every new VM needs at least one smoke test that verifies the constructor doesn't crash and the initial state is correct. Add these to `jvmTest/.../feature/<feature>/`:

```kotlin
@Test
fun initialState_isLoading() = runTest {
    val vm = createVm(param, this)
    advanceUntilIdle()
    assertIs<FooUiState.Loading>(vm.state.value)
}
```

### FakeRepositories available

All fakes are in `shared/src/commonMain/.../test/fakes/FakeRepositories.kt`:
`FakeTaskRepository`, `FakeProjectsRepository`, `FakeNotesRepository`, `FakeTagsRepository`, `FakeSavedAgendaViewsRepository`, `FakeSettingsRepository`, `FakeProfileAwareCurrentUser`, `FakeReminderRepository`, `FakeAuthRepository`.

See `singularity-todo-test-helpers` for the full test helper patterns including regression tests for draft clobbering.

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
    // Leaf screens — accessed via top-bar IconButton, NOT bottom-nav tabs
    @Serializable data object SavedAgendaList : AgendaStartRoute
    @Serializable data class SavedAgendaEdit(val viewId: String) : AgendaStartRoute {
        val id: SavedAgendaViewId get() = SavedAgendaViewId.fromString(viewId)
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

### Top-bar IconButton entry (vs full nested graph)

When the new screen is a **secondary** destination accessed from within an existing tab (Saved Views, Search, Filters), **don't create a new nested graph**. Add a leaf route to the existing graph and an IconButton to the parent's TopAppBar:

```kotlin
// AgendaStartRoute.kt — add leaf routes
@Serializable data object SavedAgendaList : AgendaStartRoute
@Serializable data class SavedAgendaEdit(val viewId: String) : AgendaStartRoute
```

Then in `AgendaNavGraph.android.kt` / `jvm.kt`:
```kotlin
entryProvider = entryProvider {
    // ... existing entries
    entry<AgendaStartRoute.SavedAgendaList> { SavedAgendaListScreen() }
    entry<AgendaStartRoute.SavedAgendaEdit> { route ->
        SavedAgendaEditScreen(viewId = SavedAgendaViewId.fromString(route.viewId))
    }
}
```

**And** in `navSavedStateConfig` (Android only):
```kotlin
navSavedStateConfig(
    // ... existing
    AgendaStartRoute.SavedAgendaList.serializer(),  // ← add both
    AgendaStartRoute.SavedAgendaEdit.serializer(),
)
```

**Then** wire the IconButton in the parent's Koin wrapper (not content):
```kotlin
// AgendaScreen.kt — has navigator, passes callback down
val navigator = LocalAgendaNavigator.current
AgendaContent(
    ...
    onSavedViewsClick = { navigator.openSavedAgendaList() },  // ← pass to content
)
```

See `singularity-todo-top-bar-entry` for the full pattern.

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
| Plugin-like extensibility (AI tools) | `factory { Tool(get(), get()) }` per tool + `single<Set<Tool<*, *>>> { getAll<Tool<*, *>>() }` aggregation (no `@IntoSet` in Koin 4.x) |
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
        vm.onIntent(<Feature>Intent.Delete(id))
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
- [ ] ViewModels take `scope: CoroutineScope` in primary constructor; secondary constructor for Koin delegates with `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` — see `singularity-todo-testable-vm` for full shape
- [ ] **Repositories and wrappers do NOT own a private `CoroutineScope`** — they accept `scope: CoroutineScope` via constructor injection; see `singularity-todo-coroutine-scopes`. A class with `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` is a bug.
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

Domain enforcement — return `Result.failure(AppError.Validation(...))` for *expected* validation failures (caller can react, retry, surface to user). Reach for `check { }` / `require { }` only for *invariant* violations that should be impossible by construction and indicate a bug:

```kotlin
if (input.parentId != null) {
    val parent = repo.findById(input.parentId).getOrNull()
        ?: return Result.failure(AppError.Validation("Parent project not found"))
    // Invariant: parents must be roots. If this fires, the call site passed a bad parent
    // — treat as a programmer error, not a user-facing validation message.
    check(parent.parentId == null) {
        "Only root projects can have children. Got parent=${parent.id} which itself has parent=${parent.parentId}"
    }
}
```

**Note on `check { ... }` / `require { ... }` semantics** — both take a *condition* plus a *lazy message lambda*. The lambda is `() -> Any`, evaluated **only when the condition is false**, and the result is converted via `.toString()` to build the exception message. It is NOT a `throw` site — `check` throws `IllegalStateException`, `require` throws `IllegalArgumentException`, regardless of what you return from the lambda. Common mistake:

```kotlin
// WRONG: suggests the lambda throws — it doesn't, it just builds a String
require(input.name.isNotBlank()) { throw AppError.Validation("Name is blank") }

// RIGHT: returns a String message; require throws IllegalArgumentException with that message
require(input.name.isNotBlank()) { "Name is blank" }
```

If you need to return a *typed* error to the caller, use `Result.failure(AppError.X(...))` — don't reach for `check { AppError.X(...) }` and expect the AppError to escape as an exception.

---

## ViewModel testability — non-negotiable checklist

When you write `*ViewModel.kt` in `presentation/viewmodel/`, the **test** in `jvmTest/.../*ViewModelTest.kt` must be trivial. If the test is hard to write, the VM is hard to test — and that means the VM is wrong, not the test.

**Checklist:**

- [ ] **`state` is `MutableStateFlow<X>`** — plain, no `.stateIn(...)`, no `WhileSubscribed`. Read with `.value`. (`AgendaViewModel`-style pure read-through VMs are the narrow exception — see section 4 and `singularity-todo-testable-vm`.)
- [ ] **`init` block uses injected `CoroutineScope`** — primary constructor takes `scope`, secondary delegates with `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` for Koin. Full shape + `Main.immediate` KMP caveat: `singularity-todo-testable-vm`.
- [ ] **No `combine(...)`** — if you find yourself writing `combine(a, b) { ... }` to assemble state, extract the combination into `init { scope.launch { _state.value = computed } }` or expose `a` and `b` as separate flows and let the Composable `combine` them.
- [ ] **No side effects inside flow operators** — never `_state.value = ...` from inside `combine`, `map`, or `onEach`. Side effects in flow operators re-run on every upstream emission. (Narrow exception: unconditional `onEach { cache.value = it }` mirroring a single upstream value — see `singularity-todo-vm-intent-pattern` → `_latestTask`.)
- [ ] **Editable state extracted to `*State` class** (e.g., `DraftState`) — testable as a pure unit, single source of truth.
- [ ] **Test passes `this` (test scope), NOT `backgroundScope`** — full rationale and the `backgroundScope` vs `this` distinction: `singularity-todo-testable-vm`.

**Example test (3 lines per case):**
```kotlin
@Test
fun nameChangedSetsIsDirty() = runTest {
    val vm = createVm(mode, this)                           // ← pass test scope
    advanceUntilIdle()
    vm.onIntent(MyIntent.NameChanged("Modified"))
    assertTrue(vm.state.value.draft.isDirty)
}
```

If your test needs Turbine, `expectMostRecentItem`, `awaitItem`, or `waitForState`, the VM is wrong. Refactor until the test is trivial.

**Full pattern guide:** see `singularity-todo-testable-vm`.

---

## Repository auth-safety — DAO `*ForUser` pattern (Phase 11-12)

Every new repository that owns user-scoped data must follow the audit-fixed pattern. Skipping this leaves the codebase exposed to the same auth-safety gap that PR12a closed.

### Mandatory RepositoryImpl constructor

```kotlin
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,  // ← mandatory
) : ProjectsRepository
```

`currentUser` is **not optional** and is **never** resolved via static singleton. Companion `ProfileAwareCurrentUser.scopedUserId` was removed in PR12b — `NoStaticProfileAwareCurrentUser` detekt rule enforces this.

### DAO mutation template

When adding a new mutation method to a DAO, give it a `*ForUser` suffix and require `userId` in the WHERE clause. The DAO method returns `Int` (rows affected) so the repository impl can enforce ownership:

```kotlin
@Query("""
    UPDATE projects
       SET parent_id = :parentId, updated_at = :ts
     WHERE id = :id AND user_id = :userId
""")
suspend fun setParentForUser(
    id: String, parentId: String?, ts: Long, userId: String,
): Int
```

Repository impl propagates the user id from `currentUser` and `require`s that rows were affected:

```kotlin
override suspend fun setParent(
    id: ProjectId, parentId: ProjectId?, updatedAt: Long,
) {
    val uid = currentUser.scopedUserId.value.value
    val rows = projectDao.setParentForUser(id.value, parentId?.value, updatedAt, uid)
    require(rows > 0) { "Project $id not found or not owned by user" }
}
```

### Fake DAO stub

When adding a real `*ForUser` method, also add a stub in `FakeAppDatabase.kt`:

```kotlin
override suspend fun setParentForUser(
    id: String, parentId: String?, ts: Long, userId: String,
): Int = mutateForUser(id, userId) { it.copy(parentId = parentId, updatedAt = ts) }
```

### No `watchById(id).first()` for one-shot reads

Replace any `dao.watchById(id).first()` (Flow allocation for a single value) with `dao.getById(id)` (direct suspend). The audit found 3 of these in `TaskRepositoryImpl` — all removed.

### Atomic bootstrap pattern

For bootstrap-style methods (seed + lookup + activate), return an immutable result carrier so callers don't re-issue a Flow subscription:

```kotlin
data class ProfileBootstrapResult(
    val created: Set<String>,
    val activated: ProfileId?,
)

suspend fun run(...): ProfileBootstrapResult { ... }
```

**Full invariants + audit checks:** see `singularity-todo-repository-architecture`.

---

## Related Skills

- `singularity-todo-domain-logic-pattern` — pure functions in `domain/logic/`: calculators, parsers, validators
- `singularity-todo-repository-architecture` — DAO `*ForUser`, atomic bootstrap, no static `ProfileAwareCurrentUser`
- `singularity-todo-testable-vm` — Canonical VM test pattern
- `singularity-todo-clean-architecture-audit` — Layer-boundary grep checks
- `singularity-todo-coroutine-scopes` — `createBackgroundScope()` placement
- `singularity-todo-feature-scaffold` (this file)
- `singularity-todo-sheet-extraction` — extracting inline sheets to separate files, callback bundle design, routing intents for sheet navigation
