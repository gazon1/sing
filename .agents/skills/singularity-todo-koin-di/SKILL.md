---
name: singularity-todo-koin-di
description: Koin Annotations 4.2.2 DI pattern for this KMP project. Use when adding new repository, use case, ViewModel, or AI tool to the DI graph. Covers @Module, @ComponentScan, @Single, @Factory, @IntoSet, @Named qualifiers, and migration from the classical domainModule() DSL. Also covers Fake test doubles registration in commonTest.
---

# Singularity TODO — Koin DI Pattern

## Current state

All domain bindings live in `shared/src/commonMain/.../core/di/Modules.kt` as a single `domainModule()` DSL (~295 lines).

Migration to Koin Annotations 4.2.2 + KSP is in progress. Use annotations for **new** code; the old DSL is still the source of truth for existing bindings.

## Key Annotations

```kotlin
@Single              // singleton — created once, shared everywhere
@Factory             // new instance every time get() is called
@Named("qualifier")  // disambiguate same-type bindings
@IntoSet             // add this bean to a Set<T> (used for 16 AI tools)
@Module              // marks a class as a DI module
@ComponentScan("pkg") // auto-register all @Single/@Factory in that package
@OptIn(KoinApiExtension::class) // required for @ComponentScan
```

## Migration: DSL → Annotations

### Before (DSL — current)
```kotlin
// Modules.kt
fun domainModule(): Module = module {
    single<TaskRepository> { TaskRepositoryImpl(get(), get()) }
    factory { DeleteTaskUseCase(get()) }
    factory { GetTasksUseCase(get()) }
    single<List<Tool<*, *>>> {
        listOf(get<RefineTaskTool>(), get<SmartRewriteTool>(), ...)
    }
}
```

### After (Annotations — target)
```kotlin
@Module
@ComponentScan("com.singularity.todo.feature.tasks")
class TasksModule

@Single
class TaskRepositoryImpl(get(), get()) : TaskRepository

@Factory
class DeleteTaskUseCase(get())

@Factory
class GetTasksUseCase(get())

@Single
class TasksViewModel(get(), get()) : ViewModel
```

## @IntoSet for multi-instance bindings (16 AI tools)

```kotlin
// Each tool annotated @Single @IntoSet:
@Single @IntoSet
class RefineTaskTool(get(), get()) : SimpleTool<...>

// KoogAgentService injects Set<Tool>:
class KoogAgentService(
    private val tools: Set<Tool<*, *>>,
    ...
) : TextGenPort {
    private val registry = ToolRegistry {
        tools.forEach { tool(it) }
    }
}
```

No more hand-written `listOf(get<X>(), get<Y>(), ...)` — Koin aggregates `@IntoSet` beans automatically.

## Qualifiers (@Named)

```kotlin
@Single @Named("device")
class DeviceDatabase(...)

@Single @Named("backup")
class BackupDatabase(...)

// Usage:
class Service(@Named("device") val db: Database)
```

## Testing: register Fake doubles

```kotlin
// In jvmTest or commonTest — override production bindings:
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
```

Or via constructor injection in the test class:
```kotlin
class NotesViewModelTest {
    private val vm = NotesViewModel(
        store = FakeNotesStore(),
        htmlPort = FakeHtmlPort(),
        settingsRepository = FakeSettingsRepository { "user-1" },
    )
    // no Koin needed — pure constructor injection
}
```

## Testing ViewModels with `scopeOverride`

ViewModels that launch coroutines in `init`, `save()`, `delete()`, etc. need the
`scopeOverride` pattern to make coroutines controllable by the test dispatcher.

### Pattern: add `scopeOverride` parameter

```kotlin
class TaskEditorViewModel(
    private val createTask: CreateTaskUseCase,
    private val clock: Clock,
    // ... other deps ...
    private val scopeOverride: CoroutineScope? = null, // ADD
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope // ADD

    private fun save() = scope.launch(Dispatchers.Unconfined) { // Unconfined for sync execution
        // ...
    }
}
```

### In tests: pass `backgroundScope`

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {
    @Test
    fun `Save with valid title creates task`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("Buy groceries"))
        advanceUntilIdle()
        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        assertFalse(fakeTaskRepo.tasks.value.isEmpty())
    }
}
```

### Key insight: `Dispatchers.Unconfined` is required

Using just `scope = backgroundScope` is NOT enough — `advanceUntilIdle()` does not
process coroutines on `backgroundScope` because they run on the test's `TestDispatcher`,
but the dispatcher isn't advanced. Adding `Dispatchers.Unconfined` makes the coroutine
execute synchronously up to the first suspension point, so state updates are visible
immediately without needing `advanceUntilIdle()` to process them.

### `scopeOverride` vs `Dispatchers.Unconfined` alone

| Approach | init block | save() | Tests |
|---|---|---|---|
| `viewModelScope` only | ❌ not controlled | ❌ not controlled | fails |
| `scopeOverride` only | ✅ controlled | ✅ controlled | still fails* |
| `scopeOverride` + `Unconfined` | ✅ sync | ✅ sync | ✅ passes |

*`advanceUntilIdle()` doesn't process `backgroundScope` coroutines

## Koin Scope for scoped lifetimes

```kotlin
@Scope
@ComponentScan("com.singularity.todo.feature.auth")
class AuthScope

// Inject @Scope scoped bean:
class SessionManager(@Named("session") val session: Session)
```

## ViewModel scope (2026-09-06)

**See also:** `singularity-todo-vm-koin-scoping` skill — full details.

- Use `viewModelOf(::VM)` for VMs without runtime parameters (auto-resolves all deps)
- Use `viewModel { (param) -> VM(param, ...) }` for VMs with runtime parameters
- Use `koinViewModel()` in Composables (not `koinInject()`)
- Use `koinViewModel { parametersOf(param) }` for runtime-parameter VMs
- **Never** use `factory {}` for ViewModel — memory leak (new instance every `get()`)
- **Never** use `koinInject()` for ViewModel — no lifecycle scoping
- `koinInject()` is correct for repositories, services, ports

## Gotchas

1. **Last-wins**: if two modules define the same type, the later-loaded one wins (same in both DSL and annotations).
2. **`@ComponentScan` requires KSP** — ensure `koin-annotations-compiler` is in `kspJvm` / `kspAndroid` configurations.
3. **`@IntoSet` only works with `Set<T>`** — declare the target as `Set<TheInterface>` in the consumer.
4. **Run blocking** in `Modules.kt` (fabricating `UserId.anonymous`): migrate to `@Factory` with a suspend builder, or pass a default `UserId` constant.
5. **Koin Annotations + KMP**: annotations compile in `commonMain`, KSP processor runs per-target (JVM/Android) separately.

## Key Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/di/Modules.kt` | Current DSL (to be migrated) |
| `shared/src/commonMain/.../core/di/PlatformModule.kt` | expect fun platformModule() |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` | Database, Ktor CIO, Koog JVM |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` | Database, Ktor OkHttp, Koog error stub |
| `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` | All fake doubles |
