---
name: singularity-todo-nav3-decorators
description: Nav3 entry decorators — ViewModelStore vs SaveableStateHolder scoping. Use when adding or modifying Nav3State.toDecoratedEntries, when tabs appear to share one ViewModel, or when a ViewModel created at startup persists across tab switches.
---

# Nav3 Entry Decorators: ViewModelStore vs SaveableStateHolder

## The two decorators

Every `NavEntry` can be decorated with two independent mechanisms:

| Decorator | What it does | Not the same as |
|---|---|---|
| `rememberSaveableStateHolderNavEntryDecorator()` | Saves/restores **UI state** (scroll position, text field values, selection) across entry recreation | ViewModel scoping |
| `rememberViewModelStoreNavEntryDecorator<NavKey>()` | Creates a **new ViewModelStore** per entry, so `koinViewModel { parametersOf(def) }` resolves a different VM | State preservation |

Both decorators are applied in `Nav3State.toDecoratedEntries`:

```kotlin
val decorators = buildList {
    add(rememberViewModelStoreNavEntryDecorator<NavKey>()) // ← must be FIRST
    addAll(entryDecorators)
    add(rememberSaveableStateHolderNavEntryDecorator())   // ← always last
}
```

## The failure mode: tabs sharing one ViewModel

**Symptom:** Tapping a tab updates the drawer's `selected` state but the agenda
content never changes. A task that only exists in Tab B's section is invisible even
after switching to Tab B. A stale `AgendaViewModel` was created at boot time and
`koinViewModel { parametersOf(def) }` always returns it.

**Root cause:** `SaveableStateHolder` does NOT create a new `ViewModelStore`.
`koinViewModel { parametersOf(def) }` reads `LocalViewModelStoreOwner.current`,
which is the window/activity — the same owner for every entry. Without
`ViewModelStoreNavEntryDecorator`, all tabs share one VM.

**Discriminating evidence:** Log `vm.identity` (or any stable property) in two
simultaneous compositions. If the values are equal across tabs, the VMs are shared.

**Fix:** Add `rememberViewModelStoreNavEntryDecorator<NavKey>()` to the decorator
list, before `SaveableStateHolder`. The VM factory will then create a fresh VM per
entry because each entry has its own `ViewModelStore`.

## When you must NOT add the decorator

- If an entry needs to share state across tab switches (e.g., a shared counter),
  that is a design problem — extract the state to a shared owner at a higher level.
  Do not work around the decorator.

## What the decorator list order means

`ViewModelStoreNavEntryDecorator` must be **first**. Decorators are applied in
order, and `SaveableStateHolder` is a pass-through that preserves state of the
owner it receives. If `SaveableStateHolder` were first, it would wrap the
window's store, not the entry's VM store.

## How to verify the fix

1. Open Tab A → note `vm.identity`
2. Switch to Tab B → note `vm.identity` (must be different from Tab A's)
3. Seed a task that only appears in Tab B's unique section
4. Switch to Tab B → the task must be visible

## See also

- `docs/decisions/2026-09-30-nav3-need-viewmodelstore-decorator.md`
- `singularity-todo-nav3-savedstate` — back stack persistence (separate concern)
