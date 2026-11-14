---
title: Create and Edit are one editor with two entry points
date: 2026-11-14
status: accepted
slug: create-and-edit-are-one-editor
---

# Create and Edit are one editor with two entry points

## Context

Issue #2882 asks for the two surfaces to stop teaching two sets of rules for one
object. The research in #2871 measured the divergence (`UXF-05`, `UXF-12`,
`UXF-24`):

| | Detail (Edit) | Create |
|---|---|---|
| commit | autosave, 300 ms | explicit `Сохранить` |
| validation | silent revert on an empty title | `Title is required` from the first frame |
| fields | 13 rows | 8 rows |

`TaskEditorContent` already renders both from one model, so the two call sites
diverged on what they *passed*: `TaskCreateScreen` handed the editor a smaller
bundle, and the detail hid two rows the create screen drew.

Two of the missing rows were not missing at the call site. The **pin** and the
**estimate** were dropped by the write path: `TaskDraft` carried the pin,
`TaskCreateIntent.PinToggled` updated it, the editor drew the row — and
`TaskDomain.buildTask` copied `CreateTaskInput` into `Task` field by field
without `isPinned`, and `createInput` did not take `estimateMinutes` at all. Both
rows were controls that reported success and changed nothing, and nothing failed
because no test read the created task's pin.

The parent tracker (#2872) sets the constraint this ADR works inside: one shared
renderer, one field order, one validation contract — but **not** one ViewModel
and **not** one save lifecycle. Create keeps an explicit `Сохранить` and a
draft; the detail keeps its debounced autosave.

## Idea

Three ways to make the two surfaces agree.

1. **Give the create screen its own editor.** Two renderers, two models. Every
   field added to one is a field to remember in the other, and the research
   already measured what happens when they drift.
2. **Make the create screen a detail on an unsaved task.** One lifecycle, one
   contract — and it costs the thing the create screen is for: a screen that
   exists before the object does. The draft, the discard guard and the explicit
   `Сохранить` all become special cases of "a task that is not real".
3. **Keep two lifecycles and one field contract.** The renderer stays shared;
   the call sites pass the same bundles for the fields a task does not need to
   exist to have; validation is gated on the same rule on both; the fields that
   genuinely need the object stay one-sided and say so.

Option 3 is what this ADR takes.

## Decision

### The field contract

1. **The rows a task can carry before it exists are on both surfaces.** That is
   the project, the tags, the recurrence, the priority, the estimate, the date,
   the start date and the pin. Create draws all of them; the detail draws all of
   them. The detail gains the **start-date row** it always hid and never
   explained.
2. **Rows that need the object stay one-sided, with the reason.** The
   completion checkbox (a task that does not exist cannot be finished), the
   checklist and the attachment *rows* (the detail draws those as sections, so a
   row too would be two ways into one list), the reminder, the deadline and the
   workflow state (they wait on the scheduling and empty-section contracts,
   #2876 and #2880) and `+ Ещё` (there is nothing hidden to list). The list is
   in `CreateAndEditFieldParityTest`, which fails when a row becomes one-sided
   without joining it.
3. **The write path carries every row the editor draws.** A row whose value the
   write drops is worse than no row: it accepts the input and reports success.
   The pin and the estimate now reach the created task.

### The validation contract

4. **A pristine form shows no error.** The red line under the title appears
   after an edit or a refused save, never on the first frame (`UXF-12`). Both
   surfaces read the message from the state the ViewModel already recomputes —
   the detail from `titleError` computed against the loaded title, the create
   screen from the state's error — so "after interaction" has one owner and not
   a flag per screen.
5. **Persistence failures stay distinguishable.** The title rule's message is
   the only one drawn under the field; every other failure keeps the snackbar.

### What stays different, on purpose

6. **Commit.** Create autosaves a draft and commits on `Сохранить`; the detail
   autosaves silently. Both already had a test (`TaskCreateDebounceTest`, the
   detail's draft slot) and both keep it.
7. **Back.** Create asks before discarding a dirty, never-saved draft; the
   detail leaves, because everything on it is saved.
8. **After save, Create lands back on the list it was opened from.** The target
   research proposed the new task's detail; the reaction to that proposal was
   recorded as a product decision to keep the list, which also keeps every
   create flow and UI test that asserts the row right after save. Landing on the
   detail is the cheaper change now that the field sets match, and it stays
   available if the product decision changes.

### The date sheet opens expanded

9. **A picker whose action row can fall off the screen opens expanded.** Create's
   date row opens the multi-date sheet; that sheet opened half-expanded, and on
   a 360×800 phone the grid filled the visible half and the confirm row sat below
   the screen edge — not on the screen, not in the accessibility tree, so the
   sheet could be neither committed nor driven by a flow. The scheduling sheet
   fixed the same defect the same way, and the multi-date sheet now does too.

## Rationale

The divergence was never in the renderer — it was in what each call site
believed the editor could do. That belief is what this ADR fixes at the seam
that can hold it: the write path and the parity test, not a shared ViewModel.

A shared ViewModel was the obvious alternative and is the one thing the parent
tracker rules out. It would have to make the draft and the saved task one state,
and the create screen's whole reason to exist is that it runs before the task
does. The two lifecycles are the feature, not the debt.

## Consequences

- Create can express an estimated, pinned, tagged, repeating, dated task in one
  pass, which is the round trip `2026-09-05-task-editor-refactor` set out to
  remove.
- The detail gains a start-date row. `startDate` still means what the deferred
  `2026-10-01-startdate-vs-duedate-semantics` ADR left open; this change does
  not decide that question, it stops the two screens disagreeing about whether
  the field exists.
- A create whose title the user never touches no longer opens with a red line.
  The button is still disabled, and a save attempt still says why.
- `CreateTaskFlowTest` asserts the pristine screen has no message where it used
  to assert the opposite; the test that a cleared title shows the message stays
  and is the half that still matters.
- `CreateAndEditFieldParityTest` holds the ordered row list on Desktop, so a call
  site that stops passing a bundle fails there rather than in a review.

## Links

- `docs/research/task-detail-ux/implementation/target-ux.md` §5
- `docs/research/task-detail-ux/comparison/ux-findings.md` — UXF-05, UXF-12, UXF-24
- `docs/decisions/2026-09-05-task-editor-refactor.md`
- `docs/decisions/2026-11-12-task-detail-scheduling-surface.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskCreateScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/TaskDomain.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/CreateAndEditFieldParityTest.kt`
