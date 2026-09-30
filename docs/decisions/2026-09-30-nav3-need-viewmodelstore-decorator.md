---
title: "Nav3State: every tab needs rememberViewModelStoreNavEntryDecorator"
date: 2026-09-30
status: accepted
tags: [navigation, nav3, koin, viewmodel, desktop-compose]
---

# Nav3State: every tab needs `rememberViewModelStoreNavEntryDecorator`

## Context

The agenda shell has three tabs — Inbox, Today, Upcoming — each backed by the same
`AgendaViewModel` type but with a different `AgendaDefinition` parameter.
`koinViewModel { parametersOf(definition) }` should return a distinct VM instance per tab,
so that switching tabs shows the correct agenda sections without re-running the full
boot sequence.

Empirical observation: tapping a tab updates the drawer highlight (`selected` state) but
the agenda content never changes. Logging `vm.identity` in two simultaneous compositions
shows the same value on both tabs. A task due tomorrow appears in the wrong bucket.

## Idea

`Nav3State.toDecoratedEntries` was applying only `rememberSaveableStateHolderNavEntryDecorator`,
which is sufficient for state preservation but insufficient for ViewModel scoping.
`SaveableStateHolder` does not create a new `ViewModelStoreOwner`; it merely saves and
restores the state of whatever owner is currently active.

`rememberViewModelStoreNavEntryDecorator` creates a new `ViewModelStore` per `NavEntry`,
so that `koinViewModel { parametersOf(def) }` inside each entry resolves a different
`LocalViewModelStoreOwner` and returns a freshly-created VM.

## Decision

`toDecoratedEntries` now prepends `rememberViewModelStoreNavEntryDecorator<NavKey>()` to
the decorator list, before `SaveableStateHolder`:

```kotlin
// Nav3State.kt — inside toDecoratedEntries(...)
val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
val decorators = buildList {
    add(viewModelStoreDecorator)
    addAll(entryDecorators)
    add(rememberSaveableStateHolderNavEntryDecorator())
}
```

Callers that pass `entryDecorators` do not need to add the ViewModelStore decorator
themselves; it is always first in the list.

## Rationale

`SaveableStateHolder` is a state-preservation mechanism, not a scoping mechanism.
A tab switch is a composition of a *different* `NavEntry`, not a re-composition of the
same entry with different content. Each entry needs its own `ViewModelStore` so that
`koinViewModel { parametersOf(def) }` (which reads `LocalViewModelStoreOwner.current`) gets
the right owner. Without this decorator, every tab shares the window-level store and the
VM created at boot time.

## Consequences

- Each agenda tab now creates its own `AgendaViewModel` on first composition.
- Tab-switch performance is O(1) per tab (first visit) vs O(n) full re-evaluate on every
  subsequent visit — this was masking the bug: the boot-time VM had already processed the
  task list once, so the stale content looked plausible.
- The fix is a single decorator applied unconditionally; no per-feature coordination needed.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3State.kt`
- Compose Navigation 3 `rememberViewModelStoreNavEntryDecorator` documentation
- Skill `singularity-todo-nav3-savedstate`
