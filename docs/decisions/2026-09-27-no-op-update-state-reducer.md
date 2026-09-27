---
status: accepted
date: 2026-09-27
deciders: Singularity Developer
---

# No-Op `updateState` Reducer Silently Discards Collected State

## Context

`e06bc8ba` (MR-1, 2026-09-25) consolidated the local MVI framework. One of its
transforms was a mechanical rewrite of the old direct state assignment:

```diff
- .collect { __state.value = it }
+ .collect { updateState { it } }
```

The two are **not** equivalent, and the difference is invisible at the call site.
Written inside a `collect` lambda, the reducer's implicit `it` shadows the collected
element:

```kotlin
todayFlow().flatMapLatest { today ->
    deps.taskRepo.observeByFilter(TaskFilter.All)
        .map { tasks -> AgendaUiState.Loaded(...) }
}
    .collect { updateState { it } }   // inner `it` = the *current* state
```

`updateState` receives `(S) -> S` and gets the identity function. `_state.update` writes
the value it already holds, so the freshly collected `Loaded` is discarded. The screen
never leaves `Loading`, even though the repository emits normally and
`AgendaEvaluator` builds the sections — the work happens and is thrown away.

The user-visible symptom was the task list never rendering under `just dr`: an
indefinite `CircularProgressIndicator` in `AgendaContent`'s `Loading` branch. Data,
profiles, filtering and the database were all fine.

Four VMs carried the pattern, with two distinct symptoms:

| VM | Initial state | Symptom |
|---|---|---|
| `AgendaViewModel` | `Loading` | task list stuck on the spinner |
| `TagGroupsViewModel` | `Loading` | tag groups screen stuck on the spinner |
| `SavedAgendaListViewModel` | `Loading` | saved agendas screen stuck on the spinner |
| `ProfileSwitcherViewModel` | data class | never refreshes; shows a stale/empty profile list |

None were caught. `AgendaViewModel` had **no test at all** — `commonTest/feature/agenda/`
held only `SavedAgendaViewModelTest` — so the sweep merged green with 1047 passing
tests over a screen that could not render. detekt had no rule covering it either:
`updateState { it }` is well-typed and short, so nothing in the standard rule set
objects to it.

## Idea

Assign the collected value directly instead of routing it through a reducer, and make
the no-op form unrepresentable in review by teaching detekt what it is.

## Decision

1. Replace all four `.collect { updateState { it } }` with `.collect { x -> setState(x) }`.
   The parameter is named deliberately: an explicit name cannot be shadowed by
   accident, and it documents that the value is already computed and needs no reducer.
2. Name the collected value after what it is at each site — `loaded`, `content`,
   `state` — rather than a generic `it`.
3. Add the `NoOpUpdateState` detekt rule (rule set `no-op-update-state`) to
   `detekt-rules`, active at error severity like the other project rules. It flags a
   `updateState` call whose sole lambda argument is the identity function — both
   `updateState { it }` and the explicitly named `updateState { s -> s }` — and does
   not flag `setState(it)`, `updateState { it.copy(...) }`, or a reducer over a
   differently-named variable.
4. Add `AgendaViewModelTest` asserting the `Loading → Loaded` transition with seeded
   tasks, and that an empty task list also reaches `Loaded` (otherwise the screen
   would spin forever before it could show its empty state).

## Rationale

`setState` is the correct primitive here: the flow has already computed the value, so
there is nothing to reduce. The shadowing trap is specific to lambdas — an implicit
`it` in a nested lambda silently wins, and the code still compiles and still type-checks
because the identity function is a valid `S -> S`.

Both test additions were verified against the defect rather than assumed: reverting the
fix makes both `AgendaViewModelTest` cases fail on the `assertIs<AgendaUiState.Loaded>`
line, and reintroducing the pattern in one file makes `:shared:detekt` fail with a
`NoOpUpdateState` finding. A rule that was never seen to fail is not known to work.

The test avoids `advanceUntilIdle` on purpose. `todayFlow` is an infinite
`while (true) { emit; delay(untilMidnight) }` loop, so advancing virtual time never
drains the scheduler; `runCurrent` executes the flow's first, immediate emission
without moving the clock. For the same reason the VM's scope gets a detached `Job` plus
an explicit `close()` — an init coroutine that never completes cannot be a child of the
`runTest` job, or `runTest` would await it and time out.

## Consequences

- The task list, tag groups and saved agendas render again on desktop and Android.
- `updateState { it }` is now a build failure, so the mechanical-rewrite trap cannot
  recur silently.
- Found while writing this up: `NoFactoryViewModelProvider` was missing from the
  `detekt-rules` service-loader file, so `NoFactoryViewModelRule` had never run despite
  having its own test. Registered and activated in the same change. The codebase has
  zero violations, so enabling it cost nothing — verified by injecting a
  `factory { ProbeViewModel() }` probe and confirming `:shared:detekt` fails on it.
  The lesson generalises: a rule set with a passing test is not evidence that the rule
  is registered, and a clean detekt run is equally consistent with "no violations" and
  "never loaded".

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/MviViewModel.kt` —
  `updateState` / `setState` are `final` by design so a subclass cannot override a writer.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoOpUpdateStateRule.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/AgendaViewModelTest.kt`
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — the sweep that introduced the two
  final state writers this decision relies on.
- `2026-09-25-local-mvi-framework` — the framework `e06bc8ba` consolidated.
