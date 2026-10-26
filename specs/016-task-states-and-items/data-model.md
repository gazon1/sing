# Data Model: Task States and Inline Task Items

**Feature**: `016-task-states-and-items`
**Date**: 2026-10-26
**Plan**: [plan.md](plan.md) · **Research**: [research.md](research.md)

Field types are given in Kotlin terms because that is the language the domain is written
in; the storage types are given alongside because the two differ for the new column.

---

## 1. `TaskWorkflowState` — NEW

The stored state of a task's work. It answers "what has the user claimed about this
task?", and nothing else.

```kotlin
enum class TaskWorkflowState {
    /** Nothing claimed. The default for every task that existed before the feature
     *  and for every task created without choosing a state. */
    Open,

    /** The user marked this as the one to do next. */
    NextUp,

    /** The user is working on this now. */
    InProgress,
}
```

| Property | Value |
|---|---|
| Storage | `tasks.workflow_state TEXT NOT NULL DEFAULT 'Open'` |
| Stored as | the enum entry **name** (`"Open"`, `"NextUp"`, `"InProgress"`) — D2 |
| Default | `Open` — for pre-existing rows, for new rows, and for rows written by a build that does not know the column |
| Nullability | not nullable; the column has a constant default, so `ALTER TABLE … ADD COLUMN` is legal in SQLite without a table rewrite |
| Independent of | the completion flag — D5 |
| Serialized on the wire as | `"workflowState"` — see [contracts/sync-wire.md](contracts/sync-wire.md) |
| Serialized in a backup as | `"workflowState"` — see [contracts/backup-format.md](contracts/backup-format.md) |

### State transitions

```text
                ┌──────────────────────────────┐
                │                              │
          ┌─────▼─────┐   set state    ┌──────▼─────┐
   any ──►│   Open    │◄───────────────│  NextUp    │
          └───────────┘                └──────┬─────┘
                                            │ set state
                                      ┌─────▼─────┐
                                      │ InProgress │
                                      └───────────┘

  completed ──► the state is frozen: it is kept, and it cannot be changed.
  reopened   ──► the state it held before completion is restored.
```

**Rules**:

1. From any non-completed state the user may move to any other non-completed state.
   There is no restricted-transition table — "next up" and "in progress" are not stages
   that must be passed through in order, and forcing an order would make the state a
   workflow engine rather than a note the user keeps.
2. A completed task's state is **read-only**. The state is displayed, not editable.
3. Completing a task does **not** change the state; reopening does **not** reset it.
4. There is no separate "start" action. Moving to `InProgress` *is* starting.

---

## 2. `TaskMarking` — NEW

The one vocabulary for what a task is marked with. It replaces `AgendaBadge` (the agenda's
single-value badge) and the parallel booleans on the task row — D6.

```kotlin
enum class TaskMarking {
    /** A prerequisite that blocks this task is unfinished. Derived. */
    Blocked,

    /** This task holds a "follows up on" relation to another task. Derived. */
    FollowUp,

    /** The task's stored workflow state is `NextUp`. */
    NextUp,

    /** The task's stored workflow state is `InProgress`. */
    InProgress,

    /** The task has a due date before today and is not finished. Derived. */
    Overdue,

    /** The task has no due date. Derived. */
    NoDate,

    /** The task is pinned. Derived. */
    Pinned,

    /** The task repeats. Derived. */
    Recurring,

    /** The task is finished. Derived. */
    Completed,
}
```

**This is a set, not a value.** A task that is blocked *and* overdue carries both
markings, and neither hides the other — that is the whole point of the change.

| Aspect | Value |
|---|---|
| Origin | `TaskMarkingPolicy.compute(task, context): ImmutableSet<TaskMarking>` |
| Stored | never — a marking is derived from the task and its neighbours |
| Ordering | the set is unordered; the **renderer** imposes a display order (D8) |
| Rendered by | `TaskMarkingsRow`, shared by the task list and the agenda |
| Replaces | `AgendaBadge` + `computeAgendaBadge`; `TaskUi.isBlocked` / `isOverdue` / `isRecurring` |

### Derivation rules

| Marking | Derived from | Rule |
|---|---|---|
| `Blocked` | `context.blockedIds` | the task's id is in the set of tasks with an unfinished `BLOCKS` prerequisite |
| `FollowUp` | `context.followUpIds` | the task owns a `FOLLOWS_UP` edge |
| `NextUp` | the task itself | `task.workflowState == NextUp` |
| `InProgress` | the task itself | `task.workflowState == InProgress` |
| `Overdue` | the task itself + `context.today` | `dueDate != null && dueDate < today && !isCompleted` |
| `NoDate` | the task itself | `dueDate == null` |
| `Pinned` | the task itself | `isPinned` |
| `Recurring` | the task itself | the task belongs to a series / carries a recurrence |
| `Completed` | the task itself | `completedAt != null` |

**`FollowUp` and `Blocked` are mutually exclusive by construction.** A `FOLLOWS_UP` edge
never makes a task wait — only a `BLOCKS` edge does — so a task cannot be both blocked
and a follow-up because of the same relation. This is the property the spec's FR-010
requires, and it is what the existing ADR of 2026-10-23 already says about the two
projections of the dependency graph.

### The context

```kotlin
data class TaskMarkingContext(
    val today: LocalDate,
    val blockedIds: Set<TaskId>,
    val followUpIds: Set<TaskId>,
)
```

Both sets are computed **once** per evaluation from the whole task list and shared, not
recomputed per task. The agenda already does this for `blockedIds`
(`AgendaEvaluator.kt:50`); the task list does the same.

### Display order

The renderer shows the markings in a fixed order so that two rows with the same markings
look the same:

```text
InProgress → NextUp → Blocked → FollowUp → Overdue → NoDate → Pinned → Recurring → Completed
```

The order puts the two **stored** states first (they are the ones the user set), then the
**blocking** facts (they change what the user can do), then the scheduling facts, then the
decorative ones. When the row is too narrow for all of them, the renderer states how many
it did not show rather than dropping one silently.

---

## 3. `Task` — MODIFIED

One field added.

```kotlin
data class Task(
    // … unchanged …
    /** The state the user has claimed for this task. Independent of completion. */
    val workflowState: TaskWorkflowState = TaskWorkflowState.Open,
)
```

**Validation**: none beyond the enum. There is no invalid value to reject — the value is
either one of the three names or the row is corrupt, and a corrupt row is a migration
problem, not a user-input problem.

**Mapping**: `TaskEntity.workflowState` ↔ `Task.workflowState`, by name, with an
unknown name read as `Open` rather than throwing — a row written by a build that knew a
fourth state must not make the whole task list unreadable.

---

## 4. `TaskEntity` — MODIFIED

One column added.

```kotlin
@Entity(
    tableName = "tasks",
    // … existing indices …
    // no index on workflow_state: the agenda matches in memory over the task list it
    // has already loaded, exactly as Selector.Blocked matches against blockedIds (D3).
)
data class TaskEntity(
    // … unchanged …
    @ColumnInfo("workflow_state") val workflowState: String = "Open",
)
```

| Aspect | Value |
|---|---|
| Column | `workflow_state` |
| Type | `TEXT NOT NULL DEFAULT 'Open'` |
| Migration | `Migration46To47` — hand-written (D3) |
| Schema export | `shared/schemas/…/47.json` |
| Round trip | `Migration46To47Test` — generated from the export |

---

## 5. `TaskUi` — MODIFIED

The row model loses three booleans and gains a set.

```kotlin
data class TaskUi(
    // … unchanged …
    // REMOVED: isBlocked, isOverdue, isRecurring
    /** Everything this task is marked with. May be empty. */
    val markings: ImmutableSet<TaskMarking> = persistentSetOf(),
)
```

`blockedBy` stays: it is the **set of prerequisite ids**, not a marking, and the detail
screen shows it as the dependency list.

---

## 6. `Selector` — MODIFIED

Two variants added to the sealed hierarchy.

```kotlin
@Serializable
@SerialName("WorkflowState")
data class WorkflowState(val state: TaskWorkflowState) : Selector

@Serializable
@SerialName("FollowUp")
data object FollowUp : Selector
```

`Selector.Blocked` already exists — it is in the hierarchy, in `typeTag` and in the
serializer registry — but it has **no** `SelectorTemplate`, so the editor never offers it.
This feature gives it one (D9).

### Matching

| Variant | Matches a task when | Needs |
|---|---|---|
| `WorkflowState(state)` | `task.workflowState == state` | nothing — the task carries it |
| `FollowUp` | `task.id in context.followUpIds` | `SelectorContext.followUpIds` |
| `Blocked` (existing) | `task.id in context.blockedIds` | `SelectorContext.blockedIds` (existing) |

`SelectorContext` gains one field:

```kotlin
data class SelectorContext(
    val today: LocalDate,
    val now: Instant,
    val blockedIds: Set<TaskId>,
    val followUpIds: Set<TaskId> = emptySet(),   // NEW
    val lastActivityOf: (Task) -> Instant = { task -> task.updatedAt },
)
```

It has a default so every existing construction site keeps compiling — the follow-up set
is empty until the caller supplies it.

### The registry discipline — five places, always together

A new `Selector` variant must be added in **all five**, and the repository has been bitten
by the fifth one before (`2026-10-19-selector-registry-completeness-guard.md`):

| # | Where | What changes |
|---|---|---|
| 1 | `Selector.kt` | the sealed hierarchy — the variant itself |
| 2 | `SelectorSerializer.kt` → `typeTag` | the discriminator `when` |
| 3 | `SelectorSerializer.kt` → `RegistryHolder.leafEntries` | the encode/decode entry — **and the `require(it.size == 14)` beside it becomes 16** |
| 4 | `SelectorSerializer.kt` → `expectedTypeTags` | the set of every type tag — **17 becomes 19** |
| 5 | `SelectorMatcher.kt` → `Selector.matches` | the match branch — the single source of truth for matching |

Plus the surrounding surfaces: `SelectorTemplate.catalogue`, `SelectorDescriptor`,
`SelectorBuilder`, `SelectorBadge.typeDescription`.

### The saved-view JSON document

A saved view is one JSON blob (`AgendaViewEntity.sectionsJson`) holding the whole
`AgendaDefinition`. Each selector is `{"_type": "<tag>", …}`.

```json
{
  "_type": "WorkflowState",
  "state": "InProgress"
}
```

```json
{ "_type": "FollowUp" }
```

An unknown `_type` makes the whole blob undecodable, so the repository drops the
undecodable view rather than failing the list — D10. See
[contracts/agenda-view-json.md](contracts/agenda-view-json.md).

---

## 7. `TaskDetailIntent` — MODIFIED

One intent added.

```kotlin
data class SetWorkflowState(val state: TaskWorkflowState) : Domain, TaskEntityIntent
```

Already present and now given a UI caller:

- `AddChecklistItem(title)` — routed and handled (`TaskChildrenSlot.kt:69,98-103`), but
  nothing on the detail screen fired it.
- `ToggleChecklistItem`, `DeleteChecklistItem` — fired by the detail screen already.
- `ToggleSubtask`, `DeleteSubtask`, `AddSubtask` — fired by the detail screen already.

---

## 8. `ChecklistItem` — UNCHANGED

No new field. The item already carries `isCompleted`, `sortOrder`, `checkedBy` and
`checkedAt`, which is everything the inline row needs.

The one change is in the repository's ordering rule: `addItem` numbers a new item
`max(sortOrder) + 1` instead of the number of existing rows (D16), because a delete
followed by an add collided and the tie was broken by rowid.

## 9. `Subtask` — UNCHANGED

A subtask stays a `Task` with a `parentTaskId`. It keeps its own screen, its own dates
and its own tags. What changes is that it is rendered inline on the parent's screen with
a real checkbox, and that ticking it still means `taskRepo.complete(subtask.id)` — the
task lifecycle write, with its prerequisite guard and its undo semantics, which a
checklist toggle deliberately does not have.

## 10. `Tag` — UNCHANGED, with one new domain rule

No new field. The new rule is a **lookup**, not a stored constraint:

```kotlin
// TagDomain
/** The tag whose name matches [name] case-insensitively after trimming, or null. */
fun findByName(tags: List<Tag>, name: String): Tag?
```

It exists because there is **no uniqueness anywhere today** — no unique index on
`tags.name`, no rule in `TagDomain`, no check in `TagsRepositoryImpl.create` — and the
requirement "a duplicate is refused and the existing tag is offered" is therefore new
logic, placed in the domain so the tags screen and the picker share it (D14).

**Rule**: trimmed, compared case-insensitively. A match selects the existing tag and the
user is told which tag was used; no second tag is created.

## 11. Validation summary

| Input | Rule | Where | Message |
|---|---|---|---|
| Task workflow state | one of three enum names | the type itself | — |
| New tag name | not blank | `TagDomain.validateName` | "Tag name cannot be blank" |
| New tag name | at most 100 code points | `TagDomain.validateName` | "Tag name too long (max 100 characters)" |
| New tag name | not already used (case-insensitively) | `TagDomain.findByName` — NEW | the existing tag is offered instead |
| New checklist item | not blank | `TaskChildrenSlot.addChecklistItem` | the row is not added |
| New subtask | not blank | `TaskChildrenSlot.addSubtask` | the row is not added |
