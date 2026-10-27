# TaskCard marking visibility

`TaskCard` is built on the domain `Task`, not on `TaskUi`, so it does not receive
a precomputed `markings` set. To show markings on a `TaskCard` surface (Search,
Archive, Project detail), thread the blocked set through the card and let the
card derive the markings itself.

## Wiring

- `TaskCard` takes `blockedTaskIds: ImmutableSet<TaskId>` (default empty) and
  `today: LocalDate` (default `systemToday()`). `DefaultTaskCardBody` computes
  `computeTaskMarkings(task, today, task.id in blockedTaskIds)` and renders
  `TaskMarkingsRow(markings = …)`.
- The screen computes the blocked set from the **whole** list it renders from:
  `remember(tasks) { Blocking.blockedTaskIds(tasks).toImmutableSet() }`, then
  passes it as `blockedTaskIds = …` to each `TaskCard`. Wrapping in `remember`
  is required — a bare `toImmutableSet()` in a `@Composable` body trips
  `NoCollectionMaterializationInComposable`.
- The parameter is `ImmutableSet`, not `Set`: a raw collection breaks
  `NoUnstableCollectionParam` and skippability.

## Why blocked-ness is a set, not a boolean

`Blocking.blockedTaskIds(allTasks)` resolves each `blockedBy` id through a map
built from the given list. A dependency hidden by a filter is absent from that
map and reads as "not blocking", so the set must be computed against the
**complete** list, never a filtered subset. `ProjectTaskListDeriver.blockedIds`
and `AgendaEvaluator` both honour this.

## Pitfalls

- Do not compute `isBlocked` per row by re-running `Blocking.blockedTaskIds`
  inside the row — it is O(edges) and makes the list quadratic.
- `TaskMarkingsRow` returns early when `shown` is empty, so rendering it
  unconditionally is safe.
- `today` defaults to `systemToday()`; pass an explicit value when the marking
  must be deterministic (tests).
