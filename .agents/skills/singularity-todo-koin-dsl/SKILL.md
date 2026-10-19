---
name: singularity-todo-koin-dsl
description: 'Canonical Koin 4.x pure-DSL patterns for this project: viewModelOf vs viewModel {}, singleOf / factoryOf, koinBridge for suspend factories, and where bindings live (per-domain *DiModule.kt, with core/di/Modules.kt as an aggregator). Use when adding or modifying any DI registration, or any koinInject()/koinViewModel() usage in a Composable.'
---

> **When to use:** Adding or modifying any DI registration in `*DiModule.kt`, or any `koinInject()`/`koinViewModel()` usage in Compose screens.

## Canonical Koin 4.x DSL Patterns

### ViewModel Registration

| DSL | When to use | Example |
|-----|-------------|---------|
| `viewModelOf(::Vm)` | All VMs **without** runtime parameters (PREFERRED) | `viewModelOf(::TaskListViewModel)` |
| `viewModel { (p: P) -> Vm(get(), p) }` | VMs **with** runtime parameters (screen nav args) | `viewModel { (taskId: TaskId) -> TaskDetailViewModel(taskId, get()) }` |
| `factory { Vm(...) }` | **NEVER** for ViewModels — causes memory leaks | — |
| `koinViewModel()` | Inject VM in Compose (no parameters) | `val vm: TaskListViewModel = koinViewModel()` |
| `koinViewModel { parametersOf(p) }` | Inject VM with runtime parameters in Compose | `val vm: TaskDetailViewModel = koinViewModel { parametersOf(taskId) }` |
| `koinInject()` | Non-ViewModel dependencies (repos, ports, services) | `val repo: TaskRepository = koinInject()` |

### Common Mistakes

**WRONG — causes memory leak (ViewModel stored in singleton scope):**
```kotlin
factory { MyViewModel(get(), get()) }  // ❌ never for ViewModel
```

**RIGHT — navigation-lifecycle bound:**
```kotlin
viewModelOf(::MyViewModel)  // ✅ no-param VM
viewModel { (id: Id) -> MyViewModel(id, get()) }  // ✅ with-param VM
```

**WRONG — doesn't inherit ViewModel lifecycle:**
```kotlin
factory { MyViewModel(get()) }  // ❌ for ViewModel
val vm = koinInject<MyViewModel>()  // ❌ manual injection
```

**RIGHT for non-ViewModel classes:**
```kotlin
factory { MyService(get(), get()) }  // ✅ fine for non-ViewModel
val service = koinInject<MyService>()  // ✅
```

### An optional dependency

`getOrNull` is **not** the optional-dependency tool. It answers "is there a definition
for this type?" — a definition that exists and *fails to be created* is not a missing
definition, it throws, and the caller cannot tell the two apart. A broken optional
binding therefore takes the whole screen down in composition instead of degrading to
"this feature is not configured".

```kotlin
// WRONG — a broken AI binding crashes the note editor:
ai = NoteAiController(improveNote = getOrNull<ImproveNoteUseCase>()?.let(::improveNoteLambda))

// RIGHT — a broken AI binding is reported and the editor continues without AI:
ai = NoteAiController(
    improveNote = getOptionalOrNull<ImproveNoteUseCase> { reportBrokenAi(it) }
        ?.let(::improveNoteLambda),
)
```

`Scope.getOptionalOrNull(onFailure)` lives in
`shared/src/commonMain/kotlin/com/singularity/todo/core/di/OptionalDependencies.kt`. A
missing definition is the normal case and is not reported; a definition that cannot be
created is a defect and goes to `onFailure`, where the call site logs it.

### The graph must be resolvable, not only registered

`TestPlatformModuleParityTest` compares the *registrations* of the production platform
module against the test one. It never resolves anything, so it cannot see a definition
whose own dependencies are missing — and that is how the note editor stayed broken on
every platform (`RewriteNoteUseCase` took the `TypedTool` *interface*, `factoryOf`
erased the generic argument, and nothing was bound for the raw type).

`DomainGraphResolutionTest`
(`desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt`)
enumerates every definition in the graph and creates each one. A definition that needs a
*runtime parameter* is skipped — the caller supplies it, and its absence is not a broken
graph. Run it after touching a module: it is the check that turns "the screen crashes
when a user reaches it" into a red test.

### Multi-Platform Module Structure

**There is no `expect`/`actual` for DI.** Per AGENTS.md the only `expect`/`actual` seam in this
project is `platformModule()`; platform divergence in bindings is expressed by two ordinary
functions, one per source set:

```kotlin
// commonMain/kotlin/.../core/di/CalendarSyncDiModule.kt
fun calendarSyncModule(): Module = module { /* shared bindings */ }

// androidMain/kotlin/.../core/di/PlatformModule.android.kt
fun platformModule(): List<Module> = listOf(
    module {
        single<ReminderScheduler> { AlarmManagerReminderScheduler(get()) }
    },
    // ...
)

// jvmMain/kotlin/.../core/di/PlatformModule.jvm.kt
fun platformModule(): List<Module> = listOf(
    module {
        single<ReminderScheduler> { JvmReminderScheduler() }
    },
    // ...
)
```

An earlier version of this skill showed `expect fun tasksModule()`. It does not exist, and
writing one would break `PlatformSeamGuardTest`, which asserts that every type bound on
Desktop is also bound on Android.

**What binds where.** `core/di/Modules.kt` is a *facade aggregator*, not the source of truth:
real bindings live in per-domain `*DiModule.kt` files and in `PlatformModule.{android,jvm}.kt`.
Compose it with list concatenation, never `includes()` — an `includes()` creates a child scope
whose bindings are invisible to sibling modules at parent level
(ADR `2026-09-27-di-module-aggregator-narrative`).

**Every platform binding is a seam and is registered.** `shared/src/jvmTest/resources/platform-seams.tsv`
classifies each one with its implementations, whether the JVM side is real or a stub, and
whether anything actually injects it. `PlatformSeamGuardTest` fails when a binding is added
without a row, when a `real` seam is really a no-op, or when an `injected` seam is referenced
nowhere. Adding a platform binding means adding a registry row.

## CalendarSyncViewModel Exception

`CalendarSyncViewModel` is **NOT** a `ViewModel` subclass — it's a plain class with 6 constructor parameters (5 Koin-resolved + 1 `CoroutineScope`). This is a known exception because it was designed before the canonical pattern.

**Registration:**
```kotlin
factory<CalendarSyncViewModel> {
    CalendarSyncViewModel(get(), get(), get(), get(), get(), createBackgroundScope())
}
```

**Injection:**
```kotlin
val viewModel: CalendarSyncViewModel = koinInject()
```

**Do NOT** use `viewModelOf` or `koinViewModel` for it.

## Constructor Parameter Limits

Koin's reflection limit is **7 constructor parameters**. If a class has 8+ parameters, use explicit parameter listing:

```kotlin
// WRONG — crashes at runtime (reflection can't instantiate)
singleOf(::BackupExporter)

// RIGHT — explicit 8 parameters
single { BackupExporter(get(), get(), get(), get(), get(), get(), get(), get()) }
```

## See also

- `AGENTS.md` — canonical ViewModel registration table (`viewModelOf` / `viewModel {}` / `koinViewModel`)
- `singularity-todo-testable-vm` — Canonical 4-arg VM constructor pattern
- `singularity-todo-vm-migration-playbook` — Migrating old VMs to canonical pattern
- `singularity-todo-di-graph-testing` — DI graph smoke tests
