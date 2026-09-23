---
title: "Canonical ViewModel constructor: scope as AutoCloseableCoroutineScope"
date: 2026-09-22
status: accepted
tags: [viewmodel, architecture, coroutines, koin]
---

## Context

`SettingsViewModel` previously had two constructors:

```kotlin
// Primary ctor: takes Set<SettingsContributor<*, *>>
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val contributors: Set<SettingsContributor<*, *>>,
    // ...
) : ViewModel()

// Secondary ctor: backward-compat shim
@Suppress("UNUSED_PARAMETER")
constructor(
    contributors: Set<SettingsContributor<*, *>>,
    settings: Any,
    savedAgendaViewsRepo: SavedAgendaViewsRepository,
    fileRevealer: FileRevealer,
    scope: AutoCloseableCoroutineScope,
) : this(
    scope = scope as CoroutineScope,
    contributors = contributors,
    // ...
)
```

Three problems:
1. `scope as CoroutineScope` cast is unnecessary and hides the real type requirement
2. `scope` param type was `CoroutineScope` (not `AutoCloseableCoroutineScope`), requiring the cast
3. Secondary constructor had 5 parameters — callers (Koin DI + tests) had to pass both the
   `Set<SettingsContributor>` and individual params, leading to the broken 7-parameter `SettingsViewModel(...)` call sites

## Decision

### 1. `AutoCloseableCoroutineScope` as primary ctor parameter

```kotlin
class SettingsViewModel(
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
    // ...
) : ViewModel() {
    init { addCloseable(scope) }
}
```

- Default value means tests can call `SettingsViewModel(...)` without a scope argument
- `addCloseable(scope)` in `init` ensures the scope is cancelled when the ViewModel is cleared
- No secondary constructor needed

### 2. Individual typed contributor parameters

```kotlin
class SettingsViewModel(
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
    private val appearanceContributor: AppearanceContributor?,
    private val notificationsContributor: NotificationsContributor?,
    private val workScheduleContributor: WorkScheduleContributor?,
    private val greetingContributor: GreetingContributor?,
    private val aiContributor: AiContributor?,
    private val defaultAgendaViewContributor: DefaultAgendaViewContributor?,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
) : ViewModel()
```

### 3. Koin DI wiring

```kotlin
viewModel {
    SettingsViewModel(
        scope = AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext),
        appearanceContributor = getOrNull<AppearanceContributor>(),
        notificationsContributor = getOrNull<NotificationsContributor>(),
        // ...
    )
}
```

`getOrNull<T>()` returns `null` if the binding isn't registered — safe for optional contributors.

## Consequences

- No cast needed — `scope` is `AutoCloseableCoroutineScope` at both call site and definition
- Tests use `testScope(backgroundScope)` to wrap the test dispatcher
- `appearanceContributor = null` is explicit — the default is intentional, not accidental
- `AutoCloseableCoroutineScope` companion factory creates a scope backed by `createBackgroundScope()`
  for production use
