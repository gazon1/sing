---
title: "Settings contributors: marker interfaces to defeat type erasure"
date: 2026-09-22
status: accepted
tags: [settings, architecture, kotlin, type-system]
---

## Context

`SettingsViewModel` previously discovered contributors at runtime using class-name matching:

```kotlin
// Before: class-name string matching — fragile, no compiler guarantee
private val aiContributor: AiSettingsContributor? =
    @Suppress("UNCHECKED_CAST")
    contributors.find {
        it::class.java.simpleName == "AiSettingsContributor"
    } as AiSettingsContributor?
```

Kotlin's generics are erased at runtime — `SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>`
has the same `getClass().getGenericSuperclass()` as any other `SettingsContributor<...>`, so
`filterIsInstance<SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>>()` returns nothing.

The `simpleName` approach works but is fragile: renaming `AiSettingsContributor` to
`AiSettingsContributorImpl` would silently break the lookup with no compiler error.

## Decision

Introduce a **marker interface** per contributor, named with a shorter suffix (`XxxContributor`)
while the concrete class keeps its original full name (`XxxSettingsContributor`):

```kotlin
// Marker: short name, no implementation
interface AiContributor : SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>

// Concrete class: original long name, implements the marker
class AiSettingsContributor(private val store: AiSettingsStore) : AiContributor {
    override fun observe(): Flow<SettingsSection.Ai> = store.observe()
    override suspend fun process(intent: SettingsIntent.Ai) = store.process(intent)
}
```

In `SettingsViewModel`:
```kotlin
class SettingsViewModel(
    // Direct typed parameter — no lookup, no suppression
    private val aiContributor: AiContributor?,
    // ...
)
```

In Koin DI (`CoreDiModule.kt`):
```kotlin
viewModel {
    SettingsViewModel(
        aiContributor = getOrNull<AiContributor>(),
        // ...
    )
}
```

Koin registers the concrete class (`AiSettingsContributor`) by its class name, while the
`AiContributor` interface type is used only for the `getOrNull<AiContributor>()` lookup — this
avoids any name conflict between interface and class.

## Consequences

- Compile-time safety: renaming `AiSettingsContributor` to `AiSettingsContributorImpl` now
  produces a **compile error** in `CoreDiModule.kt` at the `getOrNull<AiContributor>()` call site.
- No `simpleName` strings anywhere in the ViewModel — eliminated ~30 lines of accessor code
  and `@Suppress("UNCHECKED_CAST")`.
- 6 marker interfaces added: `AppearanceContributor`, `AiContributor`,
  `NotificationsContributor`, `WorkScheduleContributor`, `GreetingContributor`,
  `DefaultAgendaViewContributor`.
- `filterIsInstance<XxxContributor>()` on a `Set<SettingsContributor<*, *>>` works because the
  concrete type (`AiSettingsContributor`) is preserved — the marker interface is just the
  static type used for lookup.
