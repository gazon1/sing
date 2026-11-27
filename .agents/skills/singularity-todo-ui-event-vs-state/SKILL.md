---
name: singularity-todo-ui-event-vs-state
description: How to model one-shot UI events, routing state and continuous state separately in Singularity Todo ViewModels. Use when adding a MutableStateFlow for a dialog flag inside a Composable, when a screen has both a state StateFlow and a SharedFlow for AI results, when a remember mutableStateOf shadows VM-owned state, when deciding between .first() and .collect() for a list, or when debugging a debounced save loop. Router — load the leaf file for the specific concern.
---

# UI Event vs State — the Singularity Todo Pattern

Compose has a notorious pitfall: every `var foo by remember { mutableStateOf<X?>(null) }`
inside a Composable is **second state** that mirrors what is already in the ViewModel. It
spreads logic across two layers, makes the screen harder to test, and forces every screen to
re-invent the same `LaunchedEffect { vm.X.collectLatest { … } }` plumbing.

This project standardises on a **three-category split**. The categories are mutually
exclusive:

| Category | Lives in | Replays on rotation? | Read pattern |
|---|---|---|---|
| **UI state** — task list, current filter, editor state, loading branch | `StateFlow<UiState>` on the VM | Yes | `collectAsStateWithLifecycle()` |
| **One-shot events** — "AI Result" dialog, navigate back, snackbar, saved pulse | `MviViewModel.events` — a **Channel** behind `Flow<UiEvent>` | **No** | `CollectEvents(vm.events) { … }` → `ResultDialog` / navigation lambda |
| **Broadcast signals** — several independent subscribers that each need every value | a feature-local `MutableSharedFlow` on the VM | No | one collector per subscriber |
| **Routing state** — which sheet/dialog/menu is open | `remember { mutableStateOf }` | No | local to the composable |

> **Which one is "one-shot"?** The primary path is the Channel-backed `vm.events`
> (`EventBus`), not a `SharedFlow`. It has **one** consumer: collecting it twice splits the
> events between the collectors instead of copying them. A `SharedFlow` is the right tool
> only where a real broadcast contract exists — several subscribers, each needing every
> value. `singularity-todo-mvi-framework` has the full delivery/backpressure/close contract.


Composable exceptions that are **not** routing state:

- `Animatable` / `animateFloatAsState` for one-shot UI animations (saved-pill fade).
- A `MutableStateFlow<String>` used as a **write-port** — e.g. `queryFlow` in a search
  field. The Composable writes, the VM reads via `debounce + flatMapLatest`. That is a
  communication channel, not a duplicate of VM state.
- Transient `TextField` input **before** `onValueChange` fires — the user is still typing.

> **Scope convention in examples.** Examples use `scope.launch` for VMs built per the
> testable-VM pattern (scope injected via constructor) and `viewModelScope.launch` for
> legacy VMs. Both are equivalent in this context — the difference is only testability.
> See `singularity-todo-testable-vm` for the current convention.

## Which file to load

| Your task | Open |
|---|---|
| Declaring a `UiEvent` sealed interface, pulse events, `CollectEvents` | [`one-shot-events.md`](one-shot-events.md) |
| Writing a test that asserts an event fired | [`vm-event-tests.md`](vm-event-tests.md) |
| Checking code you are about to write for known violations | [`anti-patterns.md`](anti-patterns.md) |
| Which sheet is open; whether a flag belongs in the Composable or the VM | [`routing-state.md`](routing-state.md) |
| Who owns a draft / form value; the state-ownership boundary; state-first vs event | [`state-ownership.md`](state-ownership.md) |
| Debounced inline edit + silent save, and the write loop it can create | [`debounced-edits.md`](debounced-edits.md) |
| `.first()` snapshot vs continuous `.collect()` for a list in Compose | [`collection-strategy.md`](collection-strategy.md) |
| A Composable that keeps its own copy of VM state | [`mirror-state.md`](mirror-state.md) |

## See Also

- `singularity-todo-testable-vm` — canonical VM state shape (read-only `StateFlow` with private
  backing), scope injection, DraftState; `extraBufferCapacity = N` where a feature-local
  `SharedFlow` is genuinely needed
- `singularity-todo-vm-intent-pattern` — sealed Intent + `onIntent` dispatcher; how events
  and state interact with intents
- `singularity-todo-shared-ui-components` — `CollectEvents` helper, `NotificationHost`,
  the Composable side of this dichotomy
- `singularity-todo-inline-edit-saved-feedback` — the saved-feedback UX this supports
- ADR `docs/decisions/2026-09-15-viewmodel-state-ownership.md` — formalised the
  three-category model
- ADR `docs/decisions/2026-09-08-task-restore-undo.md` — why undo is state, not event
- ADR `docs/decisions/2026-09-05-ui-event-per-feature.md` — why per-feature `UiEvent`,
  not a global bus
