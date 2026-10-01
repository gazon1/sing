---
title: "Post-MR-13 findings — source-tab prefill + per-tab back stack"
date: 2026-10-01
tags: [navigation, agenda, fab, mr-13]
status: accepted
---

# Post-MR-13 audit findings

## MR-13: source-tab prefill (FabActionResolver + TasksStartRoute.Create.initialStartDate)

**Decision**: Updated `FabActionResolver` to pass `initialDueDate = today` when navigating from the **Today** tab, and `null` (no prefill) when navigating from **Inbox**. `TasksRoute.Create` already accepted `initialDueDate`; `TaskCreateScreen` already wired it.

### What was done

#### `FabActionResolver.kt` — prefill logic

Updated `fabActionForNav3` to pass `todayInSystemZone()` when `current.start == AgendaStartRoute.Today`:

```kotlin
AgendaStartRoute.Today -> FabAction(
    label = "Add task",
    onClick = {
        val today = todayInSystemZone()
        navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create, today))
    },
)

AgendaStartRoute.Inbox -> FabAction(
    label = "Add task",
    onClick = {
        navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create))
    },
)
```

The prefill uses `todayInSystemZone()` from `core.platform`, which is already injected elsewhere. No new DI binding needed.

#### What was already correct

- `TasksRoute.Create(val initialDueDate: LocalDate? = null)` — already accepts the parameter
- `TaskCreateScreen(initialDueDate)` — already receives and uses it
- `TasksNavGraph.jvm.kt` — already passes `route.initialDueDate` to `TaskCreateScreen`
- `FabActionResolver` already branched on `AgendaStartRoute.Today` vs `AgendaStartRoute.Inbox` — only needed to add the `today` argument

### Tests updated

`FabActionResolverTest`:
- `agendaGraph_inbox_returnsAddTask` — asserts `initialDueDate == null`
- `agendaGraph_today_returnsAddTask_withTodayDueDate` — asserts `initialDueDate == today`

### Flows created

| Flow | What it verifies |
|---|---|
| `14-prefill-from-today.yaml` | Today FAB → due date pre-filled → task lands in Today |
| `15-prefill-from-inbox.yaml` | Inbox FAB → no due date → task lands in No Date |
| `16-return-to-source-tab.yaml` | After save, still on source tab (per-tab back stack) |

### Open Q (verified, not a bug)

**Return-to-source-tab after save**: The Maestro flow `16-return-to-source-tab.yaml` tests both Today→Inbox→save and Inbox→Today→save sequences. Both pass because:

1. FAB navigates to `TasksGraph(Create, today)` and pushes it onto the agenda back stack
2. `TaskCreateScreen` navigates back on save: `navigator.back()` → inner stack pops `Create` → `onExitGraph(null)` → outer `goBack()` pops `TasksGraph`
3. Agenda back stack is back to just `AgendaGraph(Today)` — same tab, same section

This is the **per-tab back stack isolation** working correctly. No fix needed.

### Fixed

- `fabActionResolver-no-prefill` — Today FAB never passed `initialDueDate`

### Verification

```bash
./gradlew :shared:jvmTest                        # green
./gradlew :shared:detekt                          # green
maestro test Maestro/flows/tasks/14-prefill-from-today.yaml
maestro test Maestro/flows/tasks/15-prefill-from-inbox.yaml
maestro test Maestro/flows/tasks/16-return-to-source-tab.yaml
```

### Related

- `shell/FabActionResolver.kt` — today prefill logic
- `shared/src/commonTest/kotlin/com/singularity/todo/shell/FabActionResolverTest.kt` — tests updated
- `feature/nav/TasksRoute.kt` — `Create(initialDueDate)` already existed
