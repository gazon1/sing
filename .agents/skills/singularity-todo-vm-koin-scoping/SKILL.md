---
name: singularity-todo-vm-koin-scoping
description: Koin scope-DSL for ViewModels in this KMP project: viewModelOf vs viewModel {} vs factory {}, koinViewModel vs koinInject in Composables, runtime parameters via parametersOf. Use when registering a ViewModel in Modules.kt or injecting it in a Composable.
---

# ViewModel DI Scope — Koin Best Practices

## TL;DR

| Kind | DSL | When to use |
|---|---|---|
| **VM (lifecycle-scoped)** | `viewModelOf(::Vm)` | All ViewModels **without** runtime parameters |
| **VM with runtime params** | `viewModel { (param) -> Vm(...) }` | `TaskEditorViewModel(initialDueDate)` |
| **VM without lifecycle scoping** | `factory { Vm(...) }` | **Never** for ViewModel — memory leak |
| **Repository / Service** | `single { Repo(...) }` | All stateless singletons |

## Registering ViewModels in Modules.kt

### Constructor-reference (preferred — no params)

```kotlin
// Modules.kt
import org.koin.core.module.dsl.viewModelOf

val domainModule(): Module = module {
    viewModelOf(::TasksViewModel)        // 0 required params
    viewModelOf(::NotesViewModel)        // all deps resolved by Koin
    viewModelOf(::ProjectEditorViewModel)
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

### When factory {} for ViewModel is WRONG

```kotlin
// ANTI-PATTERN — creates a NEW instance on EVERY get()
factory { TasksViewModel(get(), get(), ...) }

// FIX: use viewModelOf or viewModel {}
viewModelOf(::TasksViewModel)
// or
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

## When viewModelOf doesn't work

`viewModelOf` auto-resolves all constructor parameters via Koin. It fails when:
- A parameter has no default AND is not registered in Koin
- A parameter like `SharingStarted.WhileSubscribed(5000)` is a value type (not a bean)

Workaround: wrap defaults in lambdas (already done in existing VMs):

```kotlin
// In the ViewModel constructor — use () -> T for default values
class SettingsViewModel(
    private val clock: () -> Long = { System.currentTimeMillis() },  // lambda default
)
```

## Quick reference: which DSL form

```
viewModelOf(::Vm)                    ← most VMs (auto-resolve deps)
viewModel { Vm(get(), get()) }      ← explicit deps, scoped lifecycle
factory { Vm(...) }                  ← NEVER for ViewModel
single { Vm(...) }                   ← NEVER for ViewModel (singleton)
```

## Files

- DI module: `shared/src/commonMain/.../core/di/Modules.kt`
- Decision: `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
