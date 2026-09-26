---
name: singularity-todo-koin-di
status: deprecated
Alternative: singularity-todo-koin-dsl
description: DEPRECATED. Use singularity-todo-koin-dsl instead. Koin DSL pattern for this KMP project. Use when adding new repository, use case, ViewModel, or AI tool to the DI graph. Covers domainModule() DSL as the current source of truth, layer-aware binding (interface from domain, impl from data), `singleOf`/`factoryOf` gotchas (4 documented failure modes), explicit `viewModel { }` lambda (never `viewModelOf` due to constructor ambiguity with `CoroutineScope`/`SharingStarted`), Logger injection patterns, Fake test doubles registration, and DI graph dedup.
---

# ⚠️ DEPRECATED — Use `singularity-todo-koin-dsl` instead

This skill is deprecated as of 2026-09-26. All new DI work should use `singularity-todo-koin-dsl`.

---

# Singularity TODO — Koin DI Pattern (DEPRECATED)

## Current State (as of 2026-09-09)

**All domain bindings live in `shared/src/commonMain/.../core/di/Modules.kt`** as a single `domainModule()` DSL (~300 lines). This is the **source of truth**.

**Koin Annotations 4.2.2 + KSP** are documented below as the **planned migration target** but are **NOT yet adopted** in this codebase.

## DSL is Source of Truth (NOT annotations)

```kotlin
// ✅ CURRENT — domainModule() DSL in Modules.kt
fun domainModule(): Module = module {
    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
    factoryOf(::CreateTaskUseCase)
    viewModel {
        TasksViewModel(
            taskRepo = get(),
            createTask = get(),
            updateTask = get(),
            mutations = get(),
            projectRepo = get(),
            clock = get(),
        )
    }
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

## Koin Annotations (verified against 4.2.2 jar contents)

The Koin 4.x annotations module (`io.insert-koin:koin-annotations:4.2.2`) exposes
the following annotations — **only these** exist. The compiler is the
`io.insert-koin:koin-gradle-plugin` (apply as `alias(libs.plugins.koin)`),
which runs per-target but processes `commonMain` annotated classes
without per-target KSP configuration.

```kotlin
@Singleton           // singleton — created once, shared everywhere
@Factory             // new instance every time get() is called
@Module              // marks a class as a Koin module
@ComponentScan("pkg") // auto-register all @Singleton/@Factory in that package
@Named("qualifier")  // disambiguate same-type bindings
@KoinViewModel       // registers a ViewModel (we do NOT use this — see VM skills)
@InjectedParam       // runtime param for @KoinViewModel
@Scope(...)          // custom scope grouping
@Scoped              // alternative to @Scope
@Provided            // inject an externally-provided instance
@Property("key")     // config-driven injection
@Configuration       // conditional module loading
@KoinApplication     // startup config class (replaces KoinApplication { } DSL)
@Monitor             // Android lifecycle hook
```

**Common misconceptions:**
- ❌ **`@Single`** — does NOT exist. Use `@Singleton`. Both are sometimes
  written interchangeably in old blog posts / Koin 2.x docs.
- ❌ **`@IntoSet`** — does NOT exist in 4.x annotations. It was a Koin 2.x
  feature for `Set<T>` aggregation; in 4.x the same result is achieved
  via `koin.getAll<T>()` at runtime (no annotation needed) or by binding
  `single<Set<MyType>> { getAll<MyType>() }` explicitly.
- ❌ **`koin-annotations-compiler`** artifact — does NOT exist for 4.x.
  Use the `koin-gradle-plugin` (Gradle plugin id `koin`) instead.
- ❌ **`@ComponentScan` requires per-target KSP config** — false for 4.x.
  The Gradle plugin processes `commonMain` annotated classes natively.
  Setup is: apply `alias(libs.plugins.koin)`, declare a `@Module
  @ComponentScan("pkg") class`, done.

**Use annotations when:**
- A module declares ≥15 bindings and a `@ComponentScan` over its package
  removes the manual `singleOf`/`factoryOf` repetition (e.g., `AiToolsModule`).
- Discoverability matters more than line count (new contributors see
  annotated classes without reading the DI file).

**Don't use annotations when:**
- The module has ≤10 bindings — DSL is shorter than declaring a
  `@Module` wrapper and adding annotations to each class.
- The constructor has non-Koin-bean params (Logger, function types,
  object singletons) — see "Gotchas" section for those.

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
// Explicit viewModel { } lambda — REQUIRED for VMs with scope default + sharingStarted.
// viewModelOf(::Vm) fails: Koin reflection cannot disambiguate CoroutineScope bean
// from () -> SharingStarted parameter.
viewModel {
    TasksViewModel(
        taskRepo = get(),
        createTask = get(),
        updateTask = get(),
        mutations = get(),
        projectRepo = get(),
        clock = get(),
    )
}

// Notes uses 3 separate VMs (list / editor / preview) — each scoped to its screen:
viewModel { NotesListViewModel(repo = get(), currentUser = get(), idGen = get()) }
viewModel {
    NoteEditor(
        repo = get(),
        linkRepo = get(),
        currentUser = get(),
        idGen = get(),
        autosaveScheduler = get(),
        improveNote = getOrNull(),
    )
}
viewModel { NotePreview(repo = get(), linkRepo = get(), currentUser = get()) }
```

See "Why ViewModels use `viewModel { }` (NOT `viewModelOf`)" below for the full rationale.

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

// ✅ CORRECT — always explicit viewModel { } lambda
viewModel { TasksViewModel(get(), get(), ...) }
```

For the full rationale (why `viewModelOf` is also wrong), see the dedicated section below.

## Qualifiers (@Named)

```kotlin
@Singleton @Named("device")
class DeviceDatabase(...)

@Singleton @Named("backup")
class BackupDatabase(...)

class Service(@Named("device") val db: Database)
```

## Multi-instance bindings (AI tools — current canonical)

The Koin 4.x pattern: each tool is `@Singleton` (or factory-bound), and
the consuming service receives the full set via `koin.getAll<T>()`:

```kotlin
// Each tool is registered individually — no @IntoSet in 4.x.
@Singleton
class RefineTaskTool(get(), get()) : SimpleTool<...>

@Singleton
class SmartRewriteTool(get(), get()) : SimpleTool<...>
// ... 30 more tools

class KoogAgentService(
    private val tools: Set<Tool<*, *>>,  // resolved via getAll<Tool<*, *>>()
    ...
)

// In the DI module — explicit aggregation (the only Koin 4.x option):
single<Set<Tool<*, *>>> { getAll<Tool<*, *>>() }
```

This is **longer than the old `single<List<...>> { listOf(get<X>(), get<Y>(), ...) }`**
DSL but avoids the manual `listOf` enumeration that previously caused
silent bugs (4 AI tools were omitted from `JvmAiDiGraphTest` because
they weren't in the manual list). `getAll<T>()` reflects the actual
graph state at runtime — no manual maintenance required.

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

## Testing ViewModels (canonical pattern)

The canonical VM shape uses `AutoCloseableCoroutineScope` as a **default param in the primary constructor** (NOT `scopeOverride`, NOT a secondary ctor). Tests pass scope explicitly:

```kotlin
class TasksViewModel(
    private val taskRepo: TaskRepository,
    // ... other deps ...
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
}

// In tests:
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    @Test
    fun `something`() = runTest {
        val vm = TasksViewModel(
            taskRepo = FakeTaskRepository(),
            // ... other deps ...
            scope = testScope(this),  // ← test passes scope explicitly
        )
        advanceUntilIdle()
        // ...
    }
}
```

For the canonical VM pattern with all rationale, see `singularity-todo-testable-vm`. For migration steps from old `scopeOverride` pattern, see `singularity-todo-vm-migration-playbook`.

## ⚠️ Logger injection in ViewModels

Two patterns coexist in this project — match the VM's logging needs:

### Pattern A — Logger injected via DI (for VMs where logging is essential)

VMs like `ChatViewModel`, `ProjectDetailViewModel` take `log: Logger` (non-nullable). Register the logger inline in the DI binding:

```kotlin
viewModel {
    ChatViewModel(
        log = Logger.withTag("ChatViewModel"),
        agent = get(),
        idGen = get(),
    )
}
```

### Pattern B — Logger as nullable fallback (for VMs where logging is opportunistic)

VMs like `NoteEditor` accept `logger: Logger? = null` with `private val log: Logger = logger ?: Logger.withTag("NoteEditor")` fallback. Use `getOrNull<Logger>()`:

```kotlin
viewModel {
    NoteEditor(
        repo = get(),
        linkRepo = get(),
        currentUser = get(),
        idGen = get(),
        autosaveScheduler = get(),
        improveNote = getOrNull(),
        logger = getOrNull(),  // nullable fallback
    )
}
```

**When to use which pattern:**
- VMs that always log (chat, backup, project detail) → Pattern A (DI-injected, never null)
- VMs where logging is a nice-to-have and tests often skip it → Pattern B (nullable fallback to default tag)
- Don't force one pattern — both serve legitimate needs

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

1. **Last-wins**: if two modules define the same type, the later-loaded one wins. **Deduplicate before refactoring** — Koin silently overrides, but the first binding becomes dead code (latent bug).
2. **`koin-annotations-compiler` artifact doesn't exist for 4.x.** Use the `koin-gradle-plugin` (Gradle plugin id `koin`) instead. Setup:
   ```kotlin
   // gradle/libs.versions.toml
   koin = { id = "koin", version.ref = "koin" }
   // settings.gradle.kts (the plugin id is "koin" but Koin ships it without a
   // plugin-marker artifact on Maven Central — point eachPlugin to the module)
   resolutionStrategy {
       eachPlugin {
           if (requested.id.id == "koin") {
               useModule("io.insert-koin:koin-gradle-plugin:${requested.version}")
           }
       }
   }
   // shared/build.gradle.kts
   alias(libs.plugins.koin)
   ```
3. **`@IntoSet` doesn't exist in Koin 4.x annotations.** Use `koin.getAll<T>()` at runtime instead.

---

## `singleOf` / `factoryOf` — Constructor-Reference DSL

The shorthand form `singleOf(::Class)` / `factoryOf(::Class)` is preferred for simple constructors — but **4 documented gotchas** cause runtime failures if missed. Always check these before converting from `single { ... }`.

### When `singleOf` / `factoryOf` WORKS

Use the shorthand for **concrete classes** whose constructor:
- Has **only Koin beans** (no function types, no value classes, no inline literals)
- Has **≤7 parameters** (Koin reflection limit)
- Is **not** an `object` declaration (see gotcha #4)

```kotlin
// ✅ WORKS — concrete class, simple ctor, all Koin beans
singleOf(::StubAttachmentUploadService)         // no-arg ctor
singleOf(::HlcFactory)                          // simple ctor
factoryOf(::CreateTaskUseCase)                  // 2 ctor args, both Koin beans
factoryOf(::PomodoroRepository)                 // 1 ctor arg, Koin bean
```

### Gotcha #1 — `single<T>(::Impl)` does NOT work for interface bindings

Koin's `single<T>(::Impl)` form requires Koin to resolve `Impl`'s constructor parameters via `get()` from the **interface** lookup, not the impl lookup. This doesn't work — Impl's ctor params aren't reachable through the interface.

```kotlin
// ❌ FAILS at runtime — Koin tries to resolve Impl's ctor params via the interface binding
single<NotesRepository>(::RoomNotesRepository)

// ✅ CORRECT — explicit lambda for interface bindings
single<NotesRepository> { RoomNotesRepository(get(), get(), get()) }
```

**Rule**: `single<T>(::Impl)` is for cases where Koin can already resolve Impl's ctor params from its own bean graph (which it can't through an interface binding). Use `single { Impl(get(), ...) }` for all interface bindings.

### Gotcha #2 — Function-type ctor parameters fail

Koin's reflection-based `singleOf`/`factoryOf` tries to resolve every ctor param via `get()`. A function-type param (e.g. `(Long) -> String`) gets resolved as `Function1` — which is not a registered Koin bean.

```kotlin
// ❌ FAILS at runtime — Koin tries to resolve Function1<Long, String> from DI
singleOf(::DefaultBackupFileNamer)  // ctor: (timestampToName: (Long) -> String = { ... })

// ✅ CORRECT — explicit lambda preserves Kotlin default for function-type params
single { DefaultBackupFileNamer() }  // uses class default
```

**Rule**: any class with a function-type ctor param (lambda, `(T) -> R`, etc.) must use the explicit `single { }` lambda form.

### Gotcha #3 — Non-Koin-bean value params require explicit construction

If a ctor param isn't a Koin bean (e.g. `Logger`, `Duration`, custom value classes), `singleOf` fails because Koin can't resolve it.

```kotlin
// ❌ FAILS at runtime — Logger is not a Koin bean (only LoggerHolder is)
singleOf(::BackupImporter)  // first ctor param: log: Logger

// ✅ CORRECT — construct the value inline
single {
    BackupImporter(
        Logger.withTag("BackupImporter"),
        get(), get(), get(), get(), get(), get(), get(), get(),
    )
}
```

**Rule**: classes that take `Logger`, `kotlin.time.Duration`, `kotlinx.datetime.Instant`, or other non-Koin-bean values as ctor params must use the explicit lambda form. The lambda is the right place to call `Logger.withTag(...)`, `Clock.System.now()`, etc.

### Gotcha #4 — `object` declarations don't work with `singleOf`

Kotlin `object` declarations are not constructor-referenceable — `::ObjectSingleton` is not a valid `(...) -> Class` reference.

```kotlin
// ❌ COMPILE ERROR — UlidIdGenerator is `object UlidIdGenerator : IdGenerator`
factoryOf(::UlidIdGenerator)

// ✅ CORRECT — explicit lambda for object singletons
factory<IdGenerator> { UlidIdGenerator }
```

**Rule**: stateless `object` singletons (`LoggerHolder`, `UlidIdGenerator`, etc.) use `factory<Interface> { Object }` or `single<Interface> { Object }`. The choice between `factory` and `single` is semantic — `single` returns the same instance every time (no overhead), `factory` creates a new ref per `get()` call.

### Quick reference

| Ctor characteristic | DSL form | Example |
|---|---|---|
| All params are Koin beans | `singleOf(::Class)` | `singleOf(::HlcFactory)` |
| Interface binding (Koin needs Impl) | `single<T> { Impl(...) }` | `single<NotesRepository> { RoomNotesRepository(get(), get(), get()) }` |
| Function-type ctor param | `single { Class() }` | `single { DefaultBackupFileNamer() }` |
| Non-Koin-bean value param | `single { Class(Logger.withTag(...), get(), ...) }` | `single { BackupImporter(Logger.withTag("BackupImporter"), get(), ...) }` |
| `object` declaration | `single<T> { Object }` | `factory<IdGenerator> { UlidIdGenerator }` |

---

## Why ViewModels use `viewModel { }` (NOT `viewModelOf`)

Koin's `viewModelOf(::Vm)` reflection-based DSL tries to resolve every ctor param via `get()`. When the VM has:
- A `scope: AutoCloseableCoroutineScope` param (Koin may match a `CoroutineScope` bean by mistake)
- A `sharingStarted: () -> SharingStarted` param (Koin cannot resolve this — it's not a bean)
- Multiple params of the same type (Koin resolution ambiguity, open issue #2347)

`viewModelOf` fails silently (ClassCastException) at runtime.

**Always use explicit `viewModel { }` lambda** for VMs in this project — the Kotlin compiler validates parameter assignment.

```kotlin
// ❌ FAILS — Koin can't disambiguate CoroutineScope bean from () -> SharingStarted
viewModelOf(::TasksViewModel)

// ✅ CORRECT — explicit lambda, named args make assignment compiler-checked
viewModel {
    TasksViewModel(
        taskRepo = get(),
        createTask = get(),
        updateTask = get(),
        mutations = get(),
        projectRepo = get(),
        clock = get(),
    )
}
```

For VMs with runtime params:

```kotlin
viewModel { (projectId: ProjectId) ->
    ProjectDetailViewModel(
        projectId = projectId,
        projectRepo = get(),
        // ...
    )
}
```

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
