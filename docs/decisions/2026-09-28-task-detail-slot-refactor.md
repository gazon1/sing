---
title: "TaskDetailViewModel — split into a coordinator and seven slots"
date: 2026-09-28
tags: [viewmodel, mvi, tasks, refactor, bugfix]
status: accepted
---

## Context

`TaskDetailViewModel` had grown to 524 lines owning nineteen unrelated concerns behind a
thirty-variant `onIntent` and eighteen injected dependencies, with a `combine` chain nested
three levels deep. The line count was a symptom; four real defects sat underneath it:

1. **Draft seeding re-fired on unrelated updates.** `draftState.seed(...)` ran inside the
   final `combine` transform, so it executed on every checklist, attachment, subtask, and
   reminder emission rather than once per task change. The `NoCombineSideEffect` detekt rule
   restored in the same epic found this on its first run.
2. **Backlinks never rendered.** `_linkedNotes.value` and `_linkedTasks.value` were read
   inside the final transform, but the `combine` did not depend on either flow. When the
   backlinks loaded, the assembled state was never recomputed and the card stayed empty.
3. **The write base was written from inside a pure operator.** `_latestTask.value = task`
   was assigned in a `flatMapLatest` transform.
4. **Adding a subtask created a top-level task.** `CreateTaskUseCase.invoke` re-validated the
   input through `TaskDomain.createInput(...)` and forwarded only nine of the sixteen fields.
   `parentTaskId` was among the omitted, so `buildTask` persisted a task with no parent. The
   "add subtask" action had never created a subtask.

Plus two structural faults: the file hand-rolled its own `_state`, `Channel`, and dispatcher,
tripping the `MviViewModelExt` rule the framework migration had promoted to a build failure;
and `_aiRunning` was written but never read.

## Decision

`TaskDetailViewModel` becomes `TaskDetailCoordinator` plus seven slots and one collector, per
the `FeatureSlot` pattern. The 30-intent public API and `TaskDetailUiState` are unchanged, so
`TaskDetailViewScreen` and the shared `TaskEditorContent` slot API need no edit.

| Slot | Intents | Publishes |
|---|---|---|
| `TaskEntitySlot` | 12 (dates, priority, project, tags, kind, pin, recurrence, dependencies) | project, tags, dependency candidates |
| `TaskDraftSlot` | 2 (title, description) | the editable draft; owns `seed()` and the debounced writes |
| `TaskCompletionSlot` | 1 (including the recurring branch) | completion projection for the hero row |
| `TaskChildrenSlot` | 8 (checklist, subtasks, attachments) | the three child collections |
| `TaskRemindersSlot` | 2 | reminders |
| `TaskLifecycleSlot` | 3 (delete/archive/restore) | the undo snapshot |
| `TaskAiSlot` | 1 (five actions) | `isRunning` |
| `TaskBacklinksCollector` | none | notes and tasks linking here |

Each `TaskDetailIntent.Domain` variant now also implements a sealed marker for its slot, so
dispatching a reminder intent to the checklist slot is a compile error. The coordinator routes
with a single exhaustive `when` over the sealed hierarchy, so an unrouted variant is also a
compile error.

The merge covers six inputs — task, draft, entity, children, reminders, backlinks. Completion,
lifecycle, and AI state are deliberately excluded: none appear in `TaskDetailUi`, so folding
them in would recompute the whole screen on a delete or an AI request for no visible change.

`TaskBacklinksCollector` is a plain class, not a `FeatureSlot`: it has no intent surface, and an
`onIntent` that ignored every argument would advertise a mutation path that does not exist.

## Rationale

The defects were all the same bug wearing different clothes: a flow operator that was expected
to be pure was carrying a write. Splitting the VM by concern puts each write next to the
collector that owns its trigger, which is what makes the class of bug impossible to express
rather than merely easier to spot.

The marker interfaces are the reason the split is worth its indirection. Without them a slot
would take the full 30-variant intent and ignore most of it — the old `when`, distributed. With
them, `TaskChildrenSlot.onIntent(intent: TaskChildrenIntent)` cannot be handed a
`TaskRemindersIntent`, and the compiler says so.

`CreateTaskUseCase` was fixed in its own right. The refactor surfaced it: a subtask test
asserted the created task's `parentTaskId` and found the task present but unparented. Every
field of `CreateTaskInput` is now forwarded, and the omission is documented at the call site
because the validation round-trip makes a dropped field silent.

## Consequences

- A detail ViewModel that observes more than one repository should be a coordinator plus slots,
  merged with one `combineStates` call. A slot test constructs one slot and only the fakes it
  needs.
- `combineStates`' transform is non-suspending by design: a suspending repository write inside a
  projection is a compile error. Put writes in a `collect { }` block.
- A slot's `onIntent` needs an `else` branch. It is unreachable through the coordinator's
  exhaustive `when`; it exists because the markers carry no members of their own.
- `TaskDraftSlot.seed()` is public because seeding is a one-time initialisation, not a
  projection — it cannot live in a `combine`. It is idempotent, so a remote update arriving
  mid-edit does not reset the text field.
- Slot tests pump with real `delay()`, not `advanceUntilIdle()`. The fakes' current user runs on
  `Dispatchers.Default`, which virtual time cannot advance; see R1 in the MR-1 retro. Making the
  fakes scheduler-bound is a separate change.
- The AI slot's five success paths are covered at the coordinator level rather than with five
  full AI tool chains in a slot test. The slot's own logic — the `isRunning` flag, the error
  path, and the "use case not configured" contract — is covered directly.
- `TaskAiState.isRunning` is now actually reachable; the old `_aiRunning` was write-only.
- `UpdateTaskUseCase.invoke(task)` is still deprecated for the stale-snapshot reason; migrating
  the slots to `invoke(id) { copy(...) }` would remove the need for the task-flow cache entirely
  and is the natural follow-up.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailCoordinator.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/state/TaskSlotIntents.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/CreateTask.kt`
- `2026-09-27-feature-slot-pattern.md` — the pattern this applies
- `2026-09-27-mr1-retro-findings.md` — the red test this MR deletes
- `2026-09-26-pr24-rescope.md` — the earlier rejected sub-VM split this one is distinct from
