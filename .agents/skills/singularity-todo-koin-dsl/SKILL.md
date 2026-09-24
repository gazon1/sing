# singularity-todo-koin-dsl

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

### Multi-Platform Module Structure

Koin modules are typically defined in `commonMain` and platform-specific bindings use `expect`/`actual`:

```kotlin
// commonMain
expect fun tasksModule(): Module

// androidMain
actual fun tasksModule(): Module = module {
    viewModelOf(::TaskListViewModel)
    // Android-specific: AlarmManager, EncryptedSharedPreferences, etc.
}

// jvmMain
actual fun tasksModule(): Module = module {
    viewModelOf(::TaskListViewModel)
    // JVM-specific: JDBC, desktop services, etc.
}
```

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

- `singularity-todo-vm-koin-scoping` — ViewModel scope management, AutoCloseableCoroutineScope
- `singularity-todo-testable-vm` — Canonical 4-arg VM constructor pattern
- `singularity-todo-vm-migration-playbook` — Migrating old VMs to canonical pattern
- `singularity-todo-di-graph-testing` — DI graph smoke tests
