# Quickstart: Task States and Inline Task Items

**Feature**: `016-task-states-and-items` · **Date**: 2026-10-26
**Plan**: [plan.md](plan.md) · **Model**: [data-model.md](data-model.md)

A runnable guide to proving the feature works. Each scenario says what to set up, how to
drive it and what to observe. They are written against the **production wiring** — the
real coordinator, the real repository, the real DI graph — because constitution IX holds
that a scenario which drives a test double proves only that the double works.

The scenarios are the source of the acceptance scenarios that `tasks.md` will break down;
they are not a second copy of the test suite.

---

## Prerequisites

```bash
# the repository's own wrapper — a private GRADLE_USER_HOME per worktree
./gw --version

# the host, if the Android device is needed for a Maestro flow
./sg host-load
```

| | |
|---|---|
| **Branch** | created at implementation time; the feature identifier is `016-task-states-and-items` |
| **Schema** | 46 → 47 — the migration and the regenerated export are part of the work |
| **Device** | Android only for the Maestro flows; every other scenario runs on the JVM |

---

## The gates that must be green

Run in the order `docs/architecture/verification.md` prescribes.

```bash
# 1. compile right after the connected change
./gw :shared:compileKotlinJvm

# 2. the fast test population
./gw :shared:jvmTest -Ptest.population=fast

# 3. the static gate suite
bash scripts/ci/static-gates.sh

# 4. the three gates this feature touches directly
python3 scripts/check-room-schema-integrity.py
python3 scripts/check-sync-allowlist-regenerated.py
python3 scripts/check-test-tags-catalog.py --check

# 5. the ADR gates
singularity-adr validate
openspec validate --changes
```

**The tag catalogue is regenerated, not hand-edited.** Any new `TestTags` constant is
unusable until it is:

```bash
./gw :shared:jvmTest -PupdateGoldens=true --tests "*TestTagsCatalogJvmTest*"
python3 scripts/check-test-tags-catalog.py --check
```

`TestTags.subtaskCheckbox(title)` already exists and is unused — it is the tag for
exactly the checkbox this feature adds.

---

## Scenario 1 — A task carries a state, and it survives the round trip

**Proves**: FR-001, FR-002, FR-007, SC-010.

```text
setup    a task created before the feature exists
drive    open it
expect   its workflow state is Open
```

Then, in the same JVM suite:

```text
drive    set the task's workflow state to InProgress through the coordinator's intent
expect   the task reads back as InProgress
expect   the state survives a fresh read of the repository — not an in-memory copy
```

**Where**: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/` — a repository
test against the real Room database, plus the migration round trip in
`shared/src/jvmTest/kotlin/com/singularity/todo/core/database/Migration46To47Test.kt`.

**The migration is the scenario that matters**: a database at version 46 is opened by the
new build, and the task that was there before is still there — with its notes still
pointing at it. That last part is the reason the migration is hand-written; see
[plan.md](plan.md) → *Complexity Tracking*.

---

## Scenario 2 — A state is moved deliberately, and the move is visible

**Proves**: FR-003, FR-004, FR-005, FR-006, SC-003.

```text
setup    a task on the detail screen
drive    open the state control
expect   the current state is shown, and the states it may move to are offered
drive    choose "In progress"
expect   the task's row and its detail screen both show "In progress"
expect   the change is still there after the screen is left and re-entered
```

**Where**: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/` — a
Desktop Compose flow test. Desktop is the right layer here because a **sheet on Desktop
resolves to a `Dialog` in a separate window**, so a JVM Compose test cannot address
anything inside one (`scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml:9-14`).
A control that opens a sheet is therefore driven on the JVM only where it can be reached;
the state row and its sheet are exercised through the coordinator and the repository
instead.

---

## Scenario 3 — A completed task keeps its state, and cannot be changed

**Proves**: FR-002, FR-005 (the frozen rule).

```text
setup    a task in progress
drive    complete it
expect   its workflow state is still "In progress"
expect   the state control is read-only
drive    reopen it
expect   the workflow state is still "In progress"
```

---

## Scenario 4 — Two markings show at once

**Proves**: FR-009, FR-010, FR-011, FR-012, FR-013, FR-014, SC-001, SC-002.

```text
setup    task A — blocked by an unfinished prerequisite, and overdue
setup    task B — owns a "follows up on" relation to task C, and is unfinished
drive    show the task list
expect   task A carries BOTH the blocked marking and the overdue marking
expect   task B carries the follow-up marking and NOT the blocked marking
```

The second expectation is the one that proves the two dependency projections stay apart:
a task that **follows up** on another is not waiting for it.

```text
setup    a task with more markings than fit on one row
drive    show the task list
expect   the row states how many markings it did not show
expect   it does not silently drop one
```

**Where**: a domain test over `TaskMarkingPolicy` — a pure function, so no database and
no clock — plus a Desktop Compose flow test over the list.

**The existing test that must change**: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/AgendaBadgePolicyFlowTest.kt`
asserts the old first-non-null-wins behaviour. It is replaced, not deleted: the same
assertions are re-expressed over the set.

---

## Scenario 5 — A view can be built from a state, and it keeps working

**Proves**: FR-020 … FR-026, SC-004.

```text
setup    the agenda view editor
drive    add a section
expect   sections filtered by workflow state, by blocked and by follow-up are offered
drive    choose "In progress" and save the view under a name
expect   the section shows only tasks in progress, and states how many
expect   no completed task is in it
```

Then:

```text
drive    move a task into "In progress"
expect   the saved view's contents follow, without re-creating or re-saving the view
drive    close and reopen the saved view
expect   the state filter still applies
drive    restart the application
expect   the state filter still applies
```

**Where**: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/` —
`SavedAgendaSelectorConfiguratorFlowTest.kt` and `OpenSavedViewShowsMatchingTasksFlowTest.kt`
are the existing neighbours of this scenario, and
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaViewsRepositoryImplTest.kt`
covers the persistence half.

**The "Blocked" section is the one that was always supposed to exist.** `Selector.Blocked`
has been in the hierarchy, in the discriminator and in the serializer registry since before
this feature, and had no catalogue entry — so the editor never offered it. The scenario
above fails against the code as it stands today, and that failure is the point.

---

## Scenario 6 — A saved view with an unknown selector does not take the list down

**Proves**: FR-024 (the list stays usable), D10.

```text
setup    a saved view whose document contains a selector type this build does not know
drive    open the saved-views list
expect   the list is still shown
expect   the undecodable view is dropped
expect   the other views are still there
```

**Where**: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaViewsRepositoryImplTest.kt`.

---

## Scenario 7 — A follow-up relation can be made, and is marked as one

**Proves**: FR-010, FR-011.

```text
setup    a task with no dependencies
drive    open the dependency picker from the task screen
expect   the picker is reachable — today the dependencies card only renders when a
         dependency already exists, so there is no entry point at all
drive    add a relation and choose "follows up on" rather than "blocks"
expect   the task is marked as a follow-up
expect   the task is NOT marked as blocked
```

**Where**: the repository test for the verb-aware write, plus a Desktop Compose flow
test for the picker's verb control.

---

## Scenario 8 — The task's parts are on the screen, with real checkboxes

**Proves**: FR-040 … FR-050, SC-006, SC-007, SC-008.

```text
setup    a task with two subtasks and two checklist items
drive    open the task screen
expect   the subtasks are listed, each with a checkbox
expect   the checklist items are listed, each with a checkbox
expect   each list states how many of its items are finished out of how many
```

Then:

```text
drive    tick a checklist item
expect   that item is marked finished, the checklist count updates
expect   the task is NOT marked finished
expect   no subtask changed
drive    tick a subtask
expect   that subtask is marked finished, the subtask count updates
expect   no checklist item changed
```

And the reachability case, which is the reason the checklist section must always render:

```text
setup    a task with no checklist items
drive    open the task screen
expect   the checklist section is there, and its count reads 0 of 0
expect   an item can be added from the screen — no modal opens
```

**Where**: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/` — a
Desktop Compose flow test.

**The Maestro flow that must change**: `Maestro/flows/tasks/checklist/TASK-CHECK-01.yaml`
addresses the checklist **sheet**. The detail screen never opened it (it passes
`checklist = null`) and after this feature the items are inline, so the flow is
re-pointed at the inline section. Its scenario file
(`scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml`) moves with it.

---

## Scenario 9 — A tag can be created where it is applied

**Proves**: FR-030 … FR-035, SC-005.

```text
setup    a task with no tags
drive    open the tag picker from the task screen
expect   a way to create a tag is there, next to the existing tags
drive    create a tag called "Invoice"
expect   it is created and applied to the task
expect   it is in the list of tags to choose from, without reopening the list
```

Then the two refusals:

```text
drive    create a tag called "Invoice" again
expect   no second tag is created; the existing one is offered
drive    create a tag whose name is only whitespace
expect   the reason is stated, and no tag is created
```

**Where**: a domain test over `TagDomain.findByName`, plus a Desktop Compose flow test
over the picker.

---

## Scenario 10 — The backup carries the state, and the server does too

**Proves**: the two contracts.

```text
drive    back up a task whose workflow state is "In progress"
drive    restore that backup into an empty profile
expect   the task's workflow state is "In progress"
```

**Where**: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/backup/BackupRoundTripTest.kt`.

And the wire:

```text
drive    apply a task patch that carries workflowState
expect   the server's sync_field_allowlist has a row for task.workflowState
expect   the field survives the round trip through the server
```

The second half needs the dated migration in `supabase/migrations/` to have been applied
— see [contracts/sync-wire.md](contracts/sync-wire.md) → *The server obligation*, and the
debt to avoid (open issue #1050).

---

## What "done" looks like

```bash
./gw :shared:compileKotlinJvm \
  && ./gw :shared:jvmTest -Ptest.population=fast \
  && bash scripts/ci/static-gates.sh \
  && singularity-adr validate \
  && openspec validate --changes
```

and the ten scenarios above, each driving the production path.

**Two ADRs exist before the implementation starts** (constitution II):

- *A task carries a stored workflow state* — the values, the independence from
  completion, and the absence of a stored "waiting".
- *One marking vocabulary* — the replacement of `AgendaBadge` and the row booleans with
  a single set.

The dependency-verb work extends the existing ADR of 2026-10-23 and does not need a new
one.
