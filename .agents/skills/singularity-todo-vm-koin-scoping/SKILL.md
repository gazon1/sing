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

## When `viewModelOf` doesn't work

`viewModelOf(::Vm)` auto-resolves every constructor parameter from Koin's graph via
`get()`. Three documented limitations follow from that:

1. **Kotlin default parameter values are ignored, not used.** Koin's docs are explicit:
   with any autowiring DSL (`viewModelOf`, `singleOf`, `factoryOf`), Koin tries to fill
   *every* parameter via `get()`, regardless of Kotlin defaults. A parameter like
   `clock: () -> Long = { System.currentTimeMillis() }` will NOT fall back to its
   default under `viewModelOf` — Koin tries to resolve a bean of type `() -> Long` and
   fails if none is registered. **The lambda-default pattern does not solve this.**
   Fix: switch to the explicit lambda DSL and supply the default yourself:
   ```kotlin
   // WRONG — viewModelOf ignores the Kotlin default and tries to resolve () -> Long as a bean
   viewModelOf(::SettingsViewModel)   // fails: no `() -> Long` bean registered
   class SettingsViewModel(private val clock: () -> Long = { System.currentTimeMillis() })

   // CORRECT — explicit DSL, default supplied inline
   viewModel { SettingsViewModel(clock = { System.currentTimeMillis() }) }
   ```

2. **Value types that aren't beans** (`SharingStarted.WhileSubscribed(5000)`, config
   primitives): same issue — autowiring has nothing to inject. Same fix: explicit
   `viewModel { ... }` lambda, construct the value inline.

3. **Multiple parameters of the same type** confuse constructor-reference resolution —
   a known Koin limitation, most visible with several nullable params of the same
   type (open Koin issue #2347): values can land in the wrong parameter. Fix: explicit
   lambda with **named arguments** so Kotlin's compiler (not Koin's resolution order)
   guarantees correct assignment:
   ```kotlin
   // RISKY — two String? params, viewModelOf may assign them in the wrong order
   viewModelOf(::DetailViewModel)   // DetailViewModel(p1: String?, p2: String, p3: String?)

   // SAFE — named arguments make assignment explicit and compiler-checked
   viewModel { params ->
       DetailViewModel(p1 = params.get(), p2 = params.get(), p3 = params.get())
   }
   ```

**General rule: reach for `viewModelOf` only when every constructor parameter is a plain,
uniquely-typed bean with no default you need honored.** Otherwise use the explicit
`viewModel { ... }` lambda — it costs a few extra lines and removes an entire class of
resolution ambiguity.

## Registering a testable VM (injected `CoroutineScope`)

VMs built per `singularity-todo-testable-vm` have **two constructors**: a primary one
taking an injected `scope: CoroutineScope` (tests call this directly), and a secondary
one without `scope` (Koin uses this, which builds
`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` internally).

**`viewModelOf(::Vm)` cannot be used here** — `::Vm` is ambiguous when a class has more
than one constructor, and Koin has no way to disambiguate.

Always use the explicit lambda DSL, calling the **secondary (no-scope) constructor**:

```kotlin
// SavedAgendaViewModel(deps, mode, seedStore, scope) — primary, test-only
// SavedAgendaViewModel(deps, mode, seedStore)        — secondary, Koin uses this
viewModel { (mode: SavedAgendaScreenMode) ->
    SavedAgendaViewModel(
        deps = get(),
        mode = mode,
        seedStore = get(),
        // no `scope` argument → resolves to the secondary constructor
    )
}
```

Tests never go through Koin — they call the primary constructor directly with the
test's own `CoroutineScope` (`this` inside `runTest`), per
`singularity-todo-testable-vm`. The DI module only ever sees the secondary constructor.

## Quick reference: which DSL form

```
viewModelOf(::Vm)                    ← most VMs (auto-resolve deps)
viewModel { Vm(get(), get()) }      ← explicit deps, scoped lifecycle
factory { Vm(...) }                  ← NEVER for ViewModel
single { Vm(...) }                   ← NEVER for ViewModel (singleton — state leaks across screens)
```

## See Also

- `singularity-todo-testable-vm` — the two-constructor VM shape this registration pattern accommodates; canonical explanation of `extraBufferCapacity = N` on `*Event` SharedFlows
- DI module: `shared/src/commonMain/.../core/di/Modules.kt`
- Decision: `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
