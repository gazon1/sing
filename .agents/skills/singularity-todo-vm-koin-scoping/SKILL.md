---
name: singularity-todo-vm-koin-scoping
description: Koin scope-DSL for ViewModels in this KMP project: ALWAYS use explicit `viewModel { }` lambda (never `viewModelOf` due to constructor ambiguity with `CoroutineScope`/`SharingStarted`). Covers `koinViewModel()` vs `koinInject()` in Composables, runtime parameters via `parametersOf`. Use when registering a ViewModel in DI modules or injecting it in a Composable.
---

# ViewModel DI Scope — Koin Best Practices

## TL;DR

| Kind | DSL | When to use |
|---|---|---|
| **VM (lifecycle-scoped)** | `viewModel { Vm(get(), ...) }` | **ALL** ViewModels (always explicit lambda, never `viewModelOf`) |
| **VM with runtime params** | `viewModel { (param) -> Vm(param, get(), ...) }` | `TaskEditorViewModel(initialDueDate)` |
| **VM without lifecycle scoping** | `factory { Vm(...) }` | **Never** for ViewModel — memory leak |
| **Repository / Service** | `single { Repo(...) }` / `singleOf(::Repo)` | All stateless singletons |

## Registering ViewModels in Modules.kt

### ALWAYS use explicit `viewModel { }` lambda

```kotlin
// Modules.kt
viewModel {
    TasksViewModel(
        taskRepo = get(),
        createTask = get(),
        updateTask = get(),
        mutations = get(),
        projectRepo = get(),
        clock = get(),
        // scope omitted — Kotlin default `AutoCloseableCoroutineScope()` applies
    )
}
```

### With runtime parameters

```kotlin
// TaskEditorViewModel(initialDueDate: LocalDate?)
viewModel { (initialDueDate: kotlinx.datetime.LocalDate?) ->
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

### Why `viewModelOf` is NOT used in this project

`viewModelOf(::Vm)` reflection-based DSL cannot disambiguate a `CoroutineScope` bean (matching `scope: AutoCloseableCoroutineScope`) from a `() -> SharingStarted` parameter — the assignment is undefined. This causes `ClassCastException` at runtime. Always use explicit `viewModel { }` lambda with named args.

### When factory {} for ViewModel is WRONG

```kotlin
// ANTI-PATTERN — creates a NEW instance on EVERY get()
factory { TasksViewModel(get(), get(), ...) }

// FIX: always explicit viewModel { } lambda
viewModel { TasksViewModel(get(), get(), ...) }
```

`factory {}` for a ViewModel means **every** `koinViewModel()` call creates a brand new instance — no lifecycle scoping, state is lost on navigation, memory leaks.

## Injecting in Composables

### For ViewModels — ALWAYS use koinViewModel()

```kotlin
@Composable
fun TasksScreen(
    viewModel: TasksViewModel = koinViewModel(),
) { ... }

@Composable
fun TaskEditorScreen(
    initialDueDate: LocalDate? = null,
) {
    val vm: TaskEditorViewModel = koinViewModel { parametersOf(initialDueDate) }
    // ...
}
```

### For repositories / services — keep koinInject()

```kotlin
@Composable
fun PomodoroScreen(
    timer: PomodoroTimer = koinInject(),  // not a VM, lifecycle not needed
) { ... }
```

### Anti-pattern: koinInject() for ViewModel

```kotlin
// ANTI-PATTERN — no lifecycle scoping, creates new instance each time
@Composable
fun TasksScreen(
    viewModel: TasksViewModel = koinInject(),  // WRONG
) { ... }
```

## When `viewModelOf` doesn't work (current state)

**`viewModelOf` is NOT used in this project.** All VMs use explicit `viewModel { }` lambda. Why:

`viewModelOf(::Vm)` auto-resolves every constructor parameter from Koin's graph via
`get()`. Three documented limitations follow from that:

1. **Kotlin default parameter values are ignored, not used.** Koin's docs are explicit:
   with any autowiring DSL (`viewModelOf`, `singleOf`, `factoryOf`), Koin tries to fill
   *every* parameter via `get()`, regardless of Kotlin defaults. A parameter like
   `clock: () -> Long = { System.currentTimeMillis() }` will NOT fall back to its
   default under `viewModelOf` — Koin tries to resolve a bean of type `() -> Long` and
   fails if none is registered. The lambda-default pattern does not solve this.

2. **Scope + SharingStarted ambiguity.** The canonical VM pattern has `scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope()` and (optionally) `sharingStarted: () -> SharingStarted = { ... }`. Koin's reflection-based resolution **cannot disambiguate** between `CoroutineScope` bean types and the `() -> SharingStarted` lambda parameter — the assignment is undefined.

3. **Multiple parameters of the same type** confuse constructor-reference resolution —
   a known Koin limitation, most visible with several nullable params of the same
   type (open Koin issue #2347): values can land in the wrong parameter.

**General rule: ALWAYS use the explicit `viewModel { }` lambda with named arguments.** This is non-negotiable in this project — Kotlin's compiler validates parameter assignment, and you get compile-time errors instead of runtime `ClassCastException`.

## Registering a VM with the canonical scope pattern

The canonical VM shape (per `singularity-todo-testable-vm` and `AutoCloseableCoroutineScope.kt`) is:

```kotlin
class TasksViewModel(
    private val taskRepo: TaskRepository,
    // ... other deps ...
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    // NO secondary constructor — default param handles Koin production binding
}
```

**Always register via explicit `viewModel { }` lambda**:

```kotlin
viewModel {
    TasksViewModel(
        taskRepo = get(),
        // ... other deps ...
        // no `scope` argument — Kotlin default `AutoCloseableCoroutineScope()` applies
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

Tests call the primary constructor directly with `scope = testScope(this)` (no Koin involvement). See `singularity-todo-test-helpers` for `testScope(...)` helper.

## Quick reference: which DSL form

```
viewModel { Vm(get(), get(), ...) }  ← ALL ViewModels (named args required)
viewModelOf(::Vm)                     ← NEVER (constructor ambiguity)
factory { Vm(...) }                   ← NEVER for ViewModel (memory leak)
single { Vm(...) }                    ← NEVER for ViewModel (singleton — state leaks across screens)
```

## See Also

- `singularity-todo-testable-vm` — canonical VM shape with `AutoCloseableCoroutineScope` default + DraftState pattern + `extraBufferCapacity = N` rationale
- `singularity-todo-vm-migration-playbook` — migrating old `scopeOverride` / secondary ctor VMs to the canonical shape
- `singularity-todo-koin-di` — `singleOf` / `factoryOf` gotchas + when to use each DSL form
- `singularity-todo-test-helpers` — `testScope(scope)` helper for VM tests
- DI module: `shared/src/commonMain/.../core/di/Modules.kt`
- Decision: `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` (note: this predates the default-param migration — see `2026-09-21-tier1-interface-cleanup.md` for the current pattern)
