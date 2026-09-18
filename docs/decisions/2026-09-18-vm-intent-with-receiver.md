---
title: "MR9: with(intent) stdlib receiver pattern for VM intent dispatch"
date: 2026-09-18
tags: [vm, refactor, kotlin]
---

## Context

Four VMs had `onIntent` methods with `when (intent)` branches that accessed intent properties as `intent.X`. This verbose form requires reading `intent.X` on every property access, and the repetition of `intent.` prefix clutters the branch body.

## Decision

Applied Kotlin's stdlib `with(intent) { X }` receiver pattern to replace `intent.X` with direct property access within each `when` branch.

**Before:**
```kotlin
is Intent.NameChanged -> {
    draftState.setName(intent.name)
    emitEditingState()
}
```

**After:**
```kotlin
is Intent.NameChanged -> with(intent) {
    draftState.setName(name)
    emitEditingState()
}
```

No new types, no new dependencies — pure Kotlin stdlib. Applied to:
- `SavedAgendaViewModel` — 4 data class intents
- `SavedAgendaListViewModel` — 2 data class intents
- `AgendaViewModel` — 6 data class intents
- `TaskCreateViewModel` — 5 data class intents + 2 data object intents

Data object intents (`SaveClicked`, `DiscardChanges`) are unchanged — they have no properties to access.

## Rationale

- `with(intent)` scopes the receiver to the branch body — direct property access without `intent.` prefix.
- No new API surface — stdlib only, zero dependencies.
- Simple and well-known pattern; no custom DSL markers needed.
- Each branch is self-contained and reads naturally.

## Consequences

- All new VMs in this codebase should prefer `with(intent) { ... }` for data class intents with ≥2 properties.
- Single-property intents may remain as `intent.X` for simplicity — the overhead is minimal.
- This pattern does NOT require a custom DSL marker or annotation; stdlib `with` is sufficient.

## Links

- Commit: MR9 — with(intent) stdlib receiver pattern in 4 VMs
- Modified files: `SavedAgendaViewModel.kt`, `SavedAgendaListViewModel.kt`, `AgendaViewModel.kt`, `TaskCreateViewModel.kt`
