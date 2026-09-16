---
name: singularity-todo-koin-di
description: Koin Annotations 4.2.2 DI pattern for this KMP project. Use when adding new repository, use case, ViewModel, or AI tool to the DI graph. Covers domainModule() DSL as the current source of truth, layer-aware binding (interface from domain, impl from data), @Module/@ComponentScan annotations (planned but not adopted), @Single/@Factory/@IntoSet/@Named qualifiers, and Fake test doubles registration. Also covers the scopeOverride pattern for testable coroutines in ViewModels.
---

# Singularity TODO — Koin DI Pattern

## Current State (as of 2026-09-09)

**All domain bindings live in `shared/src/commonMain/.../core/di/Modules.kt`** as a single `domainModule()` DSL (~300 lines). This is the **source of truth**.

**Koin Annotations 4.2.2 + KSP** are documented below as the **planned migration target** but are **NOT yet adopted** in this codebase.

## DSL is Source of Truth (NOT annotations)

```kotlin
// ✅ CURRENT — domainModule() DSL in Modules.kt
fun domainModule(): Module = module {
    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
    factory { CreateTaskUseCase(get(), get()) }
    viewModelOf(::TasksViewModel)
}
```

**Do NOT add `@Module @ComponentScan` annotations** for new feature bindings — add them to `domainModule()` DSL instead. This keeps all DI in one place and is easier to audit.

## Layer-Aware Binding Pattern

**The key rule:** interface comes from `domain/port/`, implementation comes from `data/`. The presentation layer never appears in DI bindings.

```kotlin
// ✅ CORRECT — interface from domain, impl from data
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl

single<TaskRepository> { TaskRepositoryImpl(get(), get()) }

// ✅ CORRECT — use case from domain/usecase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTask

factory { CreateTask(get(), get()) }

// ✅ CORRECT — ViewModel from presentation/viewmodel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskList

viewModelOf(::TaskList)

// ❌ WRONG — ViewModel imported from wrong layer
import com.singularity.todo.feature.tasks.TaskDetailViewModel  // old flat layout

// ❌ WRONG — Impl imported from domain layer
single<TaskRepository> { SomeImpl(get(), get()) }  // impl should be from data/
```

### DI imports by layer

```kotlin
// domain/model/ — no DI bindings, data classes only
import com.singularity.todo.feature.tasks.domain.model.*

// domain/port/ — interfaces only
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

// domain/usecase/ — use case classes
import com.singularity.todo.feature.tasks.domain.usecase.CreateTask
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTask

// data/ — implementations
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl

// presentation/viewmodel/ — ViewModels
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetail
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskList
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskEditor

// core/error/ — shared errors
import com.singularity.todo.core.error.AppError
```

## Key Annotations (planned, not yet adopted)

```kotlin
@Single              // singleton — created once, shared everywhere
@Factory             // new instance every time get() is called
@Named("qualifier")  // disambiguate same-type bindings
@IntoSet             // add this bean to a Set<T> (used for 16 AI tools)
@Module              // marks a class as a DI module (PLANNED)
@ComponentScan("pkg") // auto-register all @Single/@Factory in that package (PLANNED)
```

**⚠️ `@ComponentScan` + KSP for KMP:** The KSP annotation processor runs per-target (JVM/Android) separately. Before adopting annotations, verify that `@ComponentScan` works correctly with KMP source sets.

## DSL Patterns (current)

### Repository (singleton) — layer-aware
```kotlin
// Interface from domain/port/, impl from data/
single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
single<NotesRepository> { RoomNotesRepository(get(), get()) }
single<InternalLinkRepository> { InternalLinkRepositoryImpl(get(), get()) }
```

### Use cases (factory — new instance per injection)
```kotlin
// From domain/usecase/
factory { CreateTaskUseCase(get(), get()) }
factory { CreateNoteUseCase(get(), get()) }
```

### ViewModels without runtime parameters
```kotlin
// viewModelOf is preferred — auto-resolves all constructor dependencies
viewModelOf(::TasksViewModel)
viewModelOf(::ProjectsViewModel)

// Notes uses 3 separate VMs (list / editor / preview) — each scoped to its screen:
viewModel { NotesListViewModel(get(), get(), get()) }
viewModel { NoteEditor(repo=get(), currentUser=get(), idGen=get(), autosaveScheduler=get(), improveNote=getOrNull()) }
viewModel { NotePreview(repo=get(), linkRepo=get(), currentUser=get()) }
```

### ViewModels with runtime parameters
```kotlin
viewModel { (initialDueDate: LocalDate?) ->
    TaskEditorViewModel(
        deps = TaskEditorDeps(
            createTask = get(),
            updateTask = get(),
            clock = get(),
            currentUser = get(),
            taskRepository = get(),  // interface from domain/port/
            checklistUseCase = get(),
            reminderRepository = get(),
            attachmentSaver = get(),
            idGen = get(),
            timeZoneProvider = get(),
        ),
        initialDueDate = initialDueDate,
    )
}
```

### ⚠️ `factory {}` for ViewModel is WRONG
```kotlin
// ❌ WRONG — memory leak, new instance on every get()
factory { TasksViewModel(get(), get(), ...) }

// ✅ CORRECT — viewModelOf or viewModel { }
viewModelOf(::TasksViewModel)
viewModel { TasksViewModel(get(), get(), ...) }
```

## Qualifiers (@Named)

```kotlin
@Single @Named("device")
class DeviceDatabase(...)

@Single @Named("backup")
class BackupDatabase(...)

class Service(@Named("device") val db: Database)
```

## @IntoSet for multi-instance bindings (AI tools)

```kotlin
@Single @IntoSet
class RefineTaskTool(get(), get()) : SimpleTool<...>

class KoogAgentService(
    private val tools: Set<Tool<*, *>>,
    ...
)
```

No more hand-written `listOf(get<X>(), get<Y>(), ...)` — Koin aggregates `@IntoSet` beans automatically.

## Testing: register Fake doubles

```kotlin
// Option 1: override in test module
@Test
fun `search notes`() = runTest {
    startKoin {
        modules(module {
            single<NotesRepository> { FakeNotesRepository() }
        })
    }
}

// Option 2: pure constructor injection (preferred — no Koin needed)
class NotesListViewModelTest {
    private val vm = NotesListViewModel(
        repo = FakeNotesRepository(),
        currentUser = FakeCurrentUser(UserId.anonymous),
        idGen = SequenceIdGenerator(),
    )
}
```

## Testing ViewModels with scopeOverride

`NoteEditor` launches coroutines directly in `viewModelScope`. To test in synchronous `runTest` context, use `scopeOverride`:

```kotlin
class NoteEditor(
    private val repo: NotesRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val idGen: IdGenerator,
    private val autosaveScheduler: AutosaveScheduler,
    private val improveNote: ImproveNoteUseCase? = null,
    logger: Logger? = null,
    private val scopeOverride: CoroutineScope? = null,  // ADD
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
}
```

**In tests:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorTest {
    @Test
    fun `saveNow emits NavigateBack`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.saveNow()
        advanceUntilIdle()
    }
}
```

## ⚠️ Logger injection in ViewModels

`NoteEditor` accepts an optional `Logger`. Use `getOrNull<Logger>()`:

```kotlin
viewModel {
    NoteEditor(
        repo = get(),
        currentUser = get(),
        idGen = get(),
        autosaveScheduler = get(),
        improveNote = getOrNull(),
        logger = getOrNull(),  // nullable
        scopeOverride = null,
    )
}
```

**⚠️ When to switch from `viewModelOf` to `viewModel { }`:**
- `viewModelOf(::Vm)` works when all constructor parameters have Koin bindings and there are **≤7 parameters**
- When ≥8 parameters or when `getOrNull()` is needed → use `viewModel { Vm(get(), ...) }`

## Runtime Parameters in ViewModels

### The correct pattern — `viewModel { (param) -> Vm(param, get(), ...) }`

```kotlin
// In Modules.kt
viewModel { (projectId: ProjectId) ->
    ProjectDetailViewModel(
        projectId = projectId,
        projectRepo = get(),      // interface from domain/port/
        taskRepo = get(),         // interface from domain/port/
        deleteProject = get(),   // use case from domain/usecase/
        updateProject = get(),   // use case from domain/usecase/
        currentUser = get(),
        clock = get(),
    )
}

// In the screen — use parametersOf()
@Composable
fun ProjectDetailScreen(projectId: ProjectId, ...) {
    val vm: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) }
}
```

### ⚠️ Common mistake — `factory {}` for ViewModel with runtime params

```kotlin
// ❌ WRONG — memory leak
factory { (projectId: ProjectId) -> ProjectDetailViewModel(projectId, get(), ...) }

// ✅ CORRECT
viewModel { (projectId: ProjectId) -> ProjectDetailViewModel(projectId, get(), ...) }
```

### In @Preview — pass parameter directly

```kotlin
@Preview
@Composable
private fun ProjectDetailScreen_Preview() {
    PreviewThemed {
        val vm = ProjectDetailViewModel(
            projectId = sampleProjectId,
            projectRepo = FakeProjectsRepository(),
            taskRepo = FakeTaskRepository(),
            deleteProject = DeleteProjectUseCase(FakeProjectsRepository(), Clock),
            updateProject = UpdateProjectUseCase(FakeProjectsRepository(), Clock),
            currentUser = FakeProfileAwareCurrentUser(...),
            clock = Clock,
        )
        ProjectDetailContent(viewModel = vm, ...)
    }
}
```

## Gotchas

1. **Last-wins**: if two modules define the same type, the later-loaded one wins.
2. **`@ComponentScan` requires KSP** — ensure `koin-annotations-compiler` is in `kspJvm` / `kspAndroid`.
3. **`@IntoSet` only works with `Set<T>`** — declare the target as `Set<TheInterface>`.
4. **`singleOf` for repositories** — constructor-reference form doesn't support complex constructors. Use `single { RepoImpl(get(), get()) }`.

## Key Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/di/Modules.kt` | **Source of truth** — all domain bindings in DSL; includes `agendaModule()` and `calendarModule()` |
| `shared/src/commonMain/.../core/di/TasksDiModule.kt` | Feature-specific bindings (tasks, projects, etc.) |
| `shared/src/commonMain/.../feature/agenda/AgendaDiModule.kt` | AgendaViewModel binding — `viewModel { (definition: AgendaDefinition) -> AgendaViewModel(...) }` |
| `shared/src/commonMain/.../feature/calendar/CalendarDiModule.kt` | CalendarViewModel binding pattern |
| `shared/src/commonMain/.../core/di/PlatformModule.kt` | expect fun platformModule() |
| `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` | All fake doubles |

### AgendaDiModule — runtime-parameter ViewModel pattern

`AgendaViewModel` takes an `AgendaDefinition` as a runtime parameter (the definition changes per tab: Inbox, Today, Upcoming, byProject, byTag). Use `viewModel { (definition: AgendaDefinition) -> ... }`:

```kotlin
// AgendaDiModule.kt
fun agendaModule(): Module = module {
    viewModel { (definition: AgendaDefinition) ->
        AgendaViewModel(
            deps = AgendaDeps(
                taskRepo = get<TaskRepository>(),
                currentUser = get<ProfileAwareCurrentUser>(),
                logger = Logger.withTag("Agenda"),
            ),
            definition = definition,
        )
    }
}

// Modules.kt — include it
fun domainModule(): Module = module {
    // ...
    includes(agendaModule())
    includes(calendarModule())
}

// AgendaScreen.kt — inject with parametersOf
@Composable
fun AgendaScreen(definition: AgendaDefinition, ...) {
    val vm: AgendaViewModel = koinViewModel { parametersOf(definition) }
    // ...
}
```

**Key pattern:** `koinViewModel { parametersOf(definition) }` — the definition comes from the route's `AgendaStartRoute`, not from a Koin binding. Each tab creates its own VM via `parametersOf`.

### Feature-specific modules (CalendarDiModule, AgendaDiModule)

Each feature that has runtime-parameter ViewModels gets its own `*DiModule.kt` file. This keeps `Modules.kt` from growing indefinitely:

```
core/di/
├── Modules.kt              — includes(domainModule(), agendaModule(), calendarModule(), ...)
├── TasksDiModule.kt        — Tasks + Projects VM bindings
├── AgendaDiModule.kt       — AgendaViewModel bindings
└── CalendarDiModule.kt    — CalendarViewModel bindings
```

When adding a new feature, create `feature/<name>/<Name>DiModule.kt` in the feature's root (not `core/di/`) and include it from `Modules.kt`.
