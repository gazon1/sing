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

All DI is defined in `domainModule()` DSL — **do NOT add `@Module @ComponentScan` annotations** for new feature bindings. This keeps all DI in one place and is easier to audit.

## Layer-Aware Binding Pattern

**The key rule:** interface comes from `domain/port/`, implementation comes from `data/`. The presentation layer never appears in DI bindings.

| Layer | Path | DI purpose |
|---|---|---|
| domain/model/ | `feature/*/domain/model/` | No DI bindings — data classes only |
| domain/port/ | `feature/*/domain/port/` | Interfaces only |
| domain/usecase/ | `feature/*/domain/usecase/` | Use case classes |
| data/ | `feature/*/data/` | Implementations |
| presentation/viewmodel/ | `feature/*/presentation/viewmodel/` | ViewModels |

## Koin Annotations (planned migration target)

**Only these annotations exist in Koin 4.x** (`io.insert-koin:koin-annotations:4.2.2`):

```
@Singleton, @Factory, @Module, @ComponentScan("pkg"), @Named("qualifier"),
@KoinViewModel, @InjectedParam, @Scope, @Scoped, @Provided, @Property("key"),
@Configuration, @KoinApplication, @Monitor
```

**Common misconceptions (DO NOT use):**
- ❌ `@Single` — does NOT exist. Use `@Singleton`
- ❌ `@IntoSet` — does NOT exist in 4.x. Use `koin.getAll<T>()` at runtime
- ❌ `koin-annotations-compiler` artifact — does NOT exist. Use `koin-gradle-plugin`

## Key Rules

### `singleOf` / `factoryOf` — 4 Documented Gotchas

| Ctor characteristic | DSL form | Why |
|---|---|---|
| All params are Koin beans | `singleOf(::Class)` | Works for concrete classes with ≤7 params |
| Interface binding | `single<T> { Impl(...) }` | Koin can't resolve Impl's ctor params via interface lookup |
| Function-type ctor param | `single { Class() }` | Koin resolves `(T) -> R` as `Function1`, not a bean |
| Non-Koin-bean value param | `single { Class(Logger.withTag(...), get(), ...) }` | Logger, Duration, Instant must be constructed inline |
| `object` declaration | `single<T> { Object }` | `::Object` is not a valid constructor reference |

### Why `viewModelOf` Fails

Koin's `viewModelOf(::Vm)` fails when the VM has:
- A `scope: AutoCloseableCoroutineScope` param (Koin may match a `CoroutineScope` bean by mistake)
- A `sharingStarted: () -> SharingStarted` param (not a bean)
- Multiple params of the same type (resolution ambiguity)

**Always use explicit `viewModel { }` lambda** — the Kotlin compiler validates parameter assignment.

```kotlin
// ❌ WRONG — runtime ClassCastException
viewModelOf(::TasksViewModel)

// ✅ CORRECT
viewModel {
    TasksViewModel(
        taskRepo = get(),
        createTask = get(),
        // ...
    )
}

// ✅ CORRECT — with runtime params
viewModel { (projectId: ProjectId) ->
    ProjectDetailViewModel(
        projectId = projectId,
        projectRepo = get(),
        // ...
    )
}
```

### `factory {}` for ViewModel is WRONG

```kotlin
// ❌ WRONG — memory leak
factory { TasksViewModel(get(), get(), ...) }

// ✅ CORRECT
viewModel { TasksViewModel(get(), get(), ...) }
```

### Logger injection — two patterns

| Pattern | When to use | Register in DI |
|---|---|---|
| Pattern A — `log: Logger` (non-nullable) | VMs that always log (chat, backup, project detail) | `log = Logger.withTag("VmName")` inline |
| Pattern B — `logger: Logger? = null` | VMs where logging is opportunistic | `logger = getOrNull()` |

### Graph dedup — last-wins rule

If two modules define the same type, the later-loaded one wins. **Deduplicate before refactoring** — Koin silently overrides, but the first binding becomes dead code.

---

## Quick Reference

```kotlin
// Repository — singleton, layer-aware
single<TaskRepository> { TaskRepositoryImpl(get(), get()) }

// Use case — factory
factory { CreateTaskUseCase(get(), get()) }

// ViewModel without params — explicit viewModel { }
viewModel { TasksViewModel(taskRepo = get(), createTask = get(), ...) }

// ViewModel with runtime params
viewModel { (projectId: ProjectId) -> ProjectDetailViewModel(projectId, get(), ...) }

// ViewModel with many deps — use deps/params pattern
viewModel { (initialDueDate: LocalDate?) ->
    TaskEditorViewModel(
        deps = TaskEditorDeps(createTask = get(), updateTask = get(), ...),
        initialDueDate = initialDueDate,
    )
}

// Qualifier
@Singleton @Named("device")
class DeviceDatabase(...)

// Multi-instance (AI tools)
single<Set<Tool<*, *>>> { getAll<Tool<*, *>>() }

// Object singleton
factory<IdGenerator> { UlidIdGenerator }

// In Compose — use parametersOf for runtime params
@Composable
fun ProjectDetailScreen(projectId: ProjectId) {
    val vm: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) }
}
```

### Key Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/di/Modules.kt` | **Source of truth** — all domain bindings |
| `shared/src/commonMain/.../core/di/TasksDiModule.kt` | Feature-specific bindings |
| `shared/src/commonMain/.../feature/agenda/AgendaDiModule.kt` | Runtime-param VM pattern |
| `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` | All fake doubles |

---

## Quick Reference Table

| Scenario | Correct DSL | Wrong DSL |
|---|---|---|
| Interface binding | `single<T> { Impl(get(), ...) }` | `single<T>(::Impl)` |
| Function-type param | `single { Class() }` | `singleOf(::Class)` |
| Non-Koin-bean param | `single { Class(Logger.withTag(...), ...) }` | `singleOf(::Class)` |
| Kotlin `object` | `factory<T> { Object }` | `factoryOf(::Object)` |
| ViewModel (no params) | `viewModel { Vm(get(), ...) }` | `viewModelOf(::Vm)` |
| ViewModel (runtime params) | `viewModel { (p) -> Vm(p, get(), ...) }` | `factory { (p) -> Vm(p, ...) }` |
| ViewModel injection in Compose | `koinViewModel { parametersOf(p) }` | `koinInject()` |
