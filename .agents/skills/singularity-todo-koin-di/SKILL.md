---
name: singularity-todo-koin-di
description: Koin Annotations 4.2.2 DI pattern for this KMP project. Use when adding new repository, use case, ViewModel, or AI tool to the DI graph. Covers domainModule() DSL as the current source of truth, @Module/@ComponentScan annotations (planned but not adopted), @Single/@Factory/@IntoSet/@Named qualifiers, and Fake test doubles registration. Also covers the scopeOverride pattern for testable coroutines in ViewModels.
---

# Singularity TODO — Koin DI Pattern

## Current State (as of 2026-09-07)

**All domain bindings live in `shared/src/commonMain/.../core/di/Modules.kt`** as a single `domainModule()` DSL (~300 lines). This is the **source of truth**.

**Koin Annotations 4.2.2 + KSP** are documented below as the **planned migration target** but are **NOT yet adopted** in this codebase. The DSL is not going anywhere soon.

## DSL is Source of Truth (NOT annotations)

```kotlin
// ✅ CURRENT — domainModule() DSL in Modules.kt
fun domainModule(): Module = module {
    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
    factory { CreateTaskUseCase(get(), get()) }
    viewModelOf(::TasksViewModel)
    viewModelOf(::NotesViewModel)
    // ...
}
```

**Do NOT add `@Module @ComponentScan` annotations** for new feature bindings — add them to `domainModule()` DSL instead. This keeps all DI in one place and is easier to audit.

## Key Annotations (planned, not yet adopted)

```kotlin
@Single              // singleton — created once, shared everywhere
@Factory             // new instance every time get() is called
@Named("qualifier")  // disambiguate same-type bindings
@IntoSet             // add this bean to a Set<T> (used for 16 AI tools)
@Module              // marks a class as a DI module (PLANNED)
@ComponentScan("pkg") // auto-register all @Single/@Factory in that package (PLANNED)
@OptIn(KoinApiExtension::class) // required for @ComponentScan
```

**⚠️ `@ComponentScan` + KSP for KMP:** The KSP annotation processor runs per-target (JVM/Android) separately. Before adopting annotations, verify that `@ComponentScan` works correctly with KMP source sets. Currently untested in this project.

## DSL Patterns (current)

### Repository (singleton)
```kotlin
single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
single<NotesRepository> { RoomNotesRepository(get(), get()) }
```

### Use cases (factory — new instance per injection)
```kotlin
factory { CreateTaskUseCase(get(), get()) }
factory { CreateNoteUseCase(get(), get()) }
```

### ViewModels without runtime parameters
```kotlin
// viewModelOf is preferred — auto-resolves all constructor dependencies
viewModelOf(::TasksViewModel)
viewModelOf(::NotesViewModel)
viewModelOf(::ProjectsViewModel)
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
            taskRepository = get(),
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
// or
viewModel { TasksViewModel(get(), get(), ...) }
```

## Qualifiers (@Named)

```kotlin
@Single @Named("device")
class DeviceDatabase(...)

@Single @Named("backup")
class BackupDatabase(...)

// Usage:
class Service(@Named("device") val db: Database)
```

## @IntoSet for multi-instance bindings (AI tools)

```kotlin
// Each tool annotated @Single @IntoSet:
@Single @IntoSet
class RefineTaskTool(get(), get()) : SimpleTool<...>

// KoogAgentService injects Set<Tool>:
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
        modules(
            module {
                single<NotesRepository> { FakeNotesRepository() }
                single<MarkdownHtmlPort> { FakeHtmlPort() }
            }
        )
    }
    // test runs with fakes...
}

// Option 2: pure constructor injection (preferred — no Koin needed)
class NotesViewModelTest {
    private val vm = NotesViewModel(
        repo = FakeNotesRepository(),
        htmlPort = FakeHtmlPort(),
        currentUser = FakeCurrentUser(UserId.anonymous),
        idGen = SequenceIdGenerator(),
        autosaveScheduler = FakeAutosaveScheduler(),
        improveNote = null,
        logger = FakeLogger(),
    )
    // no Koin needed
}
```

## Testing ViewModels with scopeOverride

ViewModels that launch coroutines in `init`, `save()`, `delete()`, etc. need the `scopeOverride` pattern:

```kotlin
class NotesViewModel(
    private val repo: NotesRepository,
    // ... other deps ...
    private val scopeOverride: CoroutineScope? = null,  // ADD
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    fun saveNow() = scope.launch(Dispatchers.Unconfined) {
        // ...
    }
}
```

**In tests:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    @Test
    fun `saveNow emits NavigateBack`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.saveNow()
        advanceUntilIdle()
        assertIs<NotesUiEvent.NavigateBack>(vm.events.first())
    }
}
```

## ⚠️ Logger injection in ViewModels (Phase 1)

When adding `Logger` as a dependency to `NotesViewModel` (Phase 1):

- If `Logger` is **required**: use `viewModel { }` form with all 8 deps explicit
- If `Logger` is **optional** (may be null if not configured): use `getOrNull<Logger>()` with a default

```kotlin
// Phase 1: Logger is optional — use getOrNull()
viewModel {
    NotesViewModel(
        repo = get(),
        htmlPort = get(),
        currentUser = get(),
        idGen = get(),
        autosaveScheduler = get(),
        improveNote = getOrNull(),
        logger = getOrNull(),  // Phase 1: nullable logger
        scopeOverride = null,
    )
}
```

**⚠️ When to switch from `viewModelOf` to `viewModel { }`:**
- `viewModelOf(::Vm)` works when all constructor parameters have Koin bindings and there are **≤7 parameters**
- When ≥8 parameters or when `getOrNull()` is needed → use `viewModel { Vm(get(), ...) }`

## Gotchas

1. **Last-wins**: if two modules define the same type, the later-loaded one wins.
2. **`@ComponentScan` requires KSP** — ensure `koin-annotations-compiler` is in `kspJvm` / `kspAndroid` (not yet configured in this project).
3. **`@IntoSet` only works with `Set<T>`** — declare the target as `Set<TheInterface>`.
4. **Run blocking in `Modules.kt`**: migrate to `@Factory` with a suspend builder, or pass a default constant.
5. **`singleOf` for repositories** — constructor-reference form doesn't support complex constructors (per ADR `2026-09-06-koin-bridge-audit`). Use `single { RepoImpl(get(), get()) }`.

## Key Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/di/Modules.kt` | **Source of truth** — all domain bindings in DSL |
| `shared/src/commonMain/.../core/di/PlatformModule.kt` | expect fun platformModule() |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | Database, Ktor CIO, Koog JVM |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | Database, Ktor OkHttp, Koog error stub |
| `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` | All fake doubles |
