# Phase 0 Research: Task States and Inline Task Items

**Feature**: `016-task-states-and-items`
**Date**: 2026-10-26
**Spec**: [spec.md](spec.md)

Every decision below was taken against the code as it stands at schema version 46. Where a
decision constrains what the feature can promise, the constraint is stated rather than
glossed over.

---

## D1 — The workflow state is a column on `tasks`, not a separate table

**Decision**: `tasks.workflow_state TEXT NOT NULL DEFAULT 'Open'`, a column on the same
table as `priority`, `kind`, `someday` and `is_pinned`.

**Rationale**: A task has exactly one workflow state, so a separate table would be a
one-to-one relation by construction. Every task list, every agenda evaluation and every
sync document already selects from `tasks`; a join would land on the hot path of all of
them for a value that belongs to the row.

**Alternatives considered**:
- *A `task_workflow_states` table* — rejected: a one-to-one relation is a column with
  extra machinery, and it would need its own migration, its own DAO and its own join on
  every read.
- *Encoding the state into an existing field* — rejected: no existing field means
  "workflow state". `startDate` is scheduling ("from when is this actionable"), not
  workflow ("what is the user doing with it"), and overloading it would make the two
  indistinguishable.

---

## D2 — The state is stored by name in a TEXT column, not as an integer ordinal

**Decision**: The column holds the enum entry name (`"Open"`, `"NextUp"`, `"InProgress"`).

**Rationale**: This is what `priority` and `kind` already do. The value is readable in the
exported schema, in the sync allowlist and in a support request; and adding a fourth value
later cannot renumber the rows already stored.

**Alternatives considered**: An integer ordinal — rejected: it turns "adding a state" into
a data migration, and it makes the schema export opaque.

---

## D3 — Migration 46→47 is hand-written, not auto-migrated

**Decision**: A manual `Migration46To47` registered in `AppDatabaseFactory.addMigrations`,
following the precedent of `Migration45To46`.

**Rationale**: This is the decisive constraint of the whole feature. The derived
auto-migration for a changed `tasks` table rewrites the whole table; dropping it fires the
foreign key that points `notes.task_id` at a task, and the notes lose their task. The
repository already found this once — `AppDatabaseFactory.kt:51-53` says so in as many
words, and `Migration45To46` exists solely to add `tasks.series_id` without the rewrite.
A new column on `tasks` is the same shape of change and needs the same treatment.

**Alternatives considered**:
- `AutoMigration(46 → 47)` — rejected: it nulls `notes.task_id` on every upgrade.
- *A separate table* (D1) — rejected there; but note it would also have avoided this
  constraint, which is why D1 was decided on its own merits rather than on this one.

**Consequence for the plan**: the migration is `ALTER TABLE tasks ADD COLUMN workflow_state
TEXT NOT NULL DEFAULT 'Open'`, registered by hand, and `SCHEMA_VERSION` moves to 47. The
exported schema for 47 is regenerated, and the round-trip test for 46→47 is regenerated
from it.

**No index is added.** The agenda matches workflow state in memory over the task list it
has already loaded — the same way `Selector.Blocked` matches against `blockedIds`
(`AgendaEvaluator.kt:50`). An index on a column no query filters on would be weight
without a query.

---

## D4 — Three stored states, and no "waiting"

**Decision**: `TaskWorkflowState` has exactly three values — `Open` (the default), `NextUp`,
`InProgress`.

**Rationale**: This was settled with the user: a stored workflow state plus derived
markings that can appear together. The three values cover "nothing claimed", "the one I
am doing next" and "the one I am doing now". A fourth stored value, "waiting", was
considered and rejected: waiting on someone else is already derived from the task's real
prerequisites, and a stored flag for it would give one fact two sources of truth that
could disagree.

**Alternatives considered**: A stored `Waiting` — rejected as above; the blocked marking
already answers that question, and it answers it correctly when the prerequisite is
actually finished.

---

## D5 — The workflow state is independent of completion

**Decision**: Completing a task does not change its workflow state, and reopening it
restores the state it held. The state control is read-only on a completed task.

**Rationale**: The user chose "only the subtask and checklist rows get checkboxes" for the
create/detail question, which keeps the completion flag exactly as it is today — including
the create screen's present behaviour of offering no completion control before the task
exists, because there is no task to complete yet. Separately, a task that was "in progress"
when it was finished is a fact about how the work went, and clearing it on completion
loses that. The state survives; it simply stops being actionable.

**State transitions**: any non-completed state → any other non-completed state. A completed
task's state is frozen. There is no separate "start" action — moving to `InProgress` *is*
the start.

---

## D6 — One marking vocabulary, replacing both the agenda badge and the row booleans

**Decision**: A new `TaskMarking` enum in `feature/tasks/domain/model/` becomes the single
vocabulary for "what is this task marked with". `AgendaBadge` and `computeAgendaBadge` are
retired in favour of a set-valued policy, and the task row's ad-hoc booleans
(`isBlocked`, `isOverdue`, `isRecurring` on `TaskUi`) are replaced by the same set.

**Rationale**: The repository currently has two vocabularies for the same facts, and both
are defective in a way this feature exposes.

The agenda has `AgendaBadge` and a first-non-null-wins priority table
(`AgendaBadgePolicy.kt:15-25`). That table is why a task that is both blocked and overdue
shows only one of the two facts. Worse, the badge is **never rendered at all**:
`AgendaContent.kt:392-421` builds a `TaskUi` and drops `rowItem.badge` on the floor, and
`AgendaBadge` appears nowhere else outside domain logic and preview data. There is no badge
widget to widen — the surface has to be built.

Meanwhile the task list shows the same facts through booleans on `TaskUi` rendered by
`TaskMetaRow` (`TaskMetaRow.kt:55-93`). That surface already shows several facts at once,
but it cannot carry a workflow state, and it cannot express "this task is in progress *and*
blocked" as one coherent set.

**Alternatives considered**:
- *Make `AgendaBadge` a set and reuse it* — rejected: it lives in the agenda feature, and
  the task list cannot depend on the agenda (the agenda already depends on the tasks). The
  vocabulary has to live in the tasks feature or below.
- *Keep the row booleans and add two more* — rejected: a growing set of parallel booleans
  cannot answer "what is this task marked with" as a single question, and it is what made
  the single-badge priority table necessary in the first place.
- *A separate "markings" table* — rejected: a marking is a fact about a task derived from
  the task and its neighbours, not a thing that is stored on the task.

---

## D7 — The marking policy is a pure domain function over an explicit context

**Decision**: `TaskMarkingPolicy.compute(task, context): ImmutableSet<TaskMarking>`, where
the context carries `today`, the blocked task ids and the follow-up task ids.

**Rationale**: Blocked-ness and follow-up-ness are facts about the task *and its
neighbours* — they cannot be read off the task row. The agenda already computes the
blocked set once per evaluation (`AgendaEvaluator.kt:50`) and passes it through
`SelectorContext`; the task list needs the same set. A pure function with an explicit
context is testable without a database, a ViewModel or a clock, and it can be the single
answer to "which markings apply" for both surfaces.

**Alternatives considered**:
- *Compute in SQL* — rejected: blocked-ness spans `tasks` and `task_dependencies` and
  needs a join plus a "is the prerequisite finished" test; the agenda already does this in
  memory once per evaluation, and doing it once and sharing it is cheaper than doing it
  per query.
- *Compute in each ViewModel* — rejected: two ViewModels answering the same question is
  how the two vocabularies in D6 came to disagree in the first place.

---

## D8 — The row renders markings through one composable

**Decision**: A new `TaskMarkingsRow` composable in
`feature/tasks/presentation/components/list/`, rendered from `TaskUi.markings`. It shows
the markings that apply and, when the row is too narrow for all of them, states how many
it did not show rather than dropping one silently.

**Rationale**: The spec requires that one marking never replaces another and that a
truncated row says so. Both the agenda and the task list render through this composable,
so the same fact looks the same in both places.

**House style**: `SubtaskChip` in `TaskCard.kt:182-202` is the precedent for a *static*
label in this codebase, and its KDoc explains why it is a plain `Text` on a
`secondaryContainer` background rather than an `AssistChip` — "a chip is a control with a
ripple and a pressed state, and this is a static badge". The new markings follow that
precedent rather than inventing a second one.

---

## D9 — Two new agenda selectors, and `Blocked` gets a catalogue entry

**Decision**: Add `Selector.WorkflowState(state)` and `Selector.FollowUp`. Give the
existing `Selector.Blocked` a `SelectorTemplate` entry so the editor can offer it.

**Rationale**: `Selector.Blocked` already exists in the sealed hierarchy, in `typeTag` and
in the serializer registry — but it has **no** `SelectorTemplate`, so the editor never
offers it and the user cannot build a blocked view. The feature is not "add a blocked
selector"; it is "give the selector that already exists a catalogue entry".

**The registry discipline** — a new `Selector` variant must be added in five places at
once, and the repository has been bitten by the fifth one before
(`2026-10-19-selector-registry-completeness-guard.md`):

1. the sealed hierarchy in `Selector.kt`;
2. the `typeTag` `when` in `SelectorSerializer.kt`;
3. one entry in `RegistryHolder.leafEntries` — **and** the count in
   `require(it.size == 14)` beside it;
4. an entry in `expectedTypeTags` — the set that says how many type tags exist (currently
   17, becoming 19);
5. a branch in `Selector.matches` in `SelectorMatcher.kt`, which the KDoc calls the single
   source of truth for matching.

Plus the surrounding surfaces: `SelectorTemplate.catalogue`, `SelectorDescriptor`,
`SelectorBuilder`, `SelectorBadge.typeDescription`, and the `Selector.matches` branch.

**`SelectorContext` gains `followUpIds`.** The workflow-state selector needs no new
context — the task carries its own state — but the follow-up selector does, exactly as
`Selector.Blocked` needs `blockedIds`.

---

## D10 — A saved view written by a newer build must not take the whole list down

**Decision**: The saved-view read path drops a view it cannot decode instead of failing
the list. Forward compatibility across builds is best-effort: a view created by a newer
build and read by an older one is **dropped**, not corrupted.

**Rationale**: A saved view is one JSON blob (`AgendaViewEntity.sectionsJson`) holding the
whole `AgendaDefinition`. `SelectorSerializer.deserialize` does
`registry.allEntries.getValue(type)` — an unknown `_type` throws, and because the blob is
one document, one unknown selector takes the **entire view** down, not just one section.
This feature adds two type tags, so it makes that path reachable in practice for the first
time: a view saved on a device that has the new selectors, read on a device that does not,
would otherwise fail the whole saved-views list.

**Alternatives considered**:
- *Make the decoder skip unknown sections* — rejected: `SelectorSerializer` decodes one
  selector to one selector; there is no list level at which it could skip, and inventing a
  sentinel variant would put a lie into the domain.
- *Accept the failure* — rejected: a new selector type is a normal consequence of adding
  a feature, and the cost of it should not be that a device can no longer show its saved
  views.

---

## D11 — The follow-up relation needs a verb control and a wider read path

**Decision**: `DependencyPickerSheet` gains a verb control, the write path uses
`TaskRepository.setDependency(from, to, verb, enabled)` instead of the hard-coded
`DependencyVerb.BLOCKS`, and the read path widens so `FOLLOWS_UP` edges are visible.

**Rationale**: The `DependencyVerb` enum already carries `FOLLOWS_UP` and the schema
already stores it (`Migration24To25`), but **no UI writes it** — every write goes through
`DependencyVerb.BLOCKS.name` (`TaskRepositoryImpl.kt:801,813`;
`TaskDocumentWriter.kt:57-75`) — and **no read path surfaces it**: `TaskExtras.kt:57,89,134`
filter to `BLOCKS`. So a follow-up relation is storable but neither creatable nor visible.
A follow-up *marking* therefore requires all three changes; the marking alone would mark
nothing.

**Two constraints that come with it**:

- *The dependencies card is the only entry point to the picker, and it only renders when
  there is already a dependency* — `TaskEditorContent.kt:194` requires
  `blockedBy.isNotEmpty() && availableTasks.isNotEmpty()`. With zero dependencies the user
  cannot open the picker at all. The plan fixes this, because otherwise no user can create
  the relation the marking is about.
- *The relation does not travel between devices.* `task_dependencies` is a cross-ref that
  does not implement `SyncableEntity`, and the sync document carries only the blocking
  projection (`dependsOn`). The existing ADR records this as deferred work
  (`2026-10-01-typed-task-dependency-links.md:105,124`). So a follow-up marking is
  **device-local** — a task can be marked as a follow-up on the device where the relation
  was made and not on another. The spec's requirement is about what the user sees, and
  this limitation is stated rather than hidden.

**Naming**: the ordering graph is the one to read. The repository's ADR of 2026-10-23
already names the two projections apart — `getOrderingEdgeIdsForUser` is every edge, and
`getBlockingEdgeIdsForUser(taskId, userId, verb)` is the edges with that verb. A follow-up
marking asks the ordering question and filters to the `FOLLOWS_UP` verb.

---

## D12 — The state control is an attribute row and a sheet

**Decision**: A `workflowState` row in `TaskEditorContent`, backed by a new
`TaskEditorSheet.WorkflowState` and a new sheet composable — the same shape as the
priority row.

**Rationale**: The house pattern for a single-value task attribute on this screen is a
`TaskAttributeCard` row that opens a sheet: priority, project, recurrence, estimate all
work that way, and `TaskEditorSheetsHost` has one `when` branch per sheet. A segmented
control or a radio group would be a second pattern for the same kind of value, and there
is no precedent for either on this screen. The row plus the sheet is two taps, which is
what the spec's success criterion asks for.

**Alternatives considered**: An inline row of three buttons — rejected: it does not fit the
screen's density, and it has no precedent to match.

---

## D13 — Tag creation happens in the picker sheet, reusing the existing use case

**Decision**: `TagsPickerSheet` gains a create affordance that calls `CreateTagUseCase`
and adds the new `TagId` to the sheet's own `selected` list. The existing "Done" button
then carries the result to the task through the existing `SetTags` path.

**Rationale**: The sheet already owns the selection and already reports it once, on "Done"
(`TagsPickerSheet.kt:99-103`), and that path already reaches `TaskRepository.setTags`.
Adding the new id to `selected` therefore applies the tag to the task with **no change** to
the sheet host, the coordinator or the intent path.

**Alternatives considered**:
- *A task-detail-scoped ViewModel* — rejected: it would need a new Koin binding and a new
  entry in the deliberately narrow `TaskDetailDeps` bundle, for a flow the sheet can
  complete on its own.
- *`koinInject()` for the use case* — chosen over nothing, and consistent with the
  repository's rule that `koinInject()` is for repositories and services; `CreateTagUseCase`
  is a service, and it is already bound as a `factory` in `TagsDiModule.kt:53-55`.

---

## D14 — The duplicate-tag rule is case-insensitive on the trimmed name

**Decision**: A tag whose trimmed name already exists (compared case-insensitively) is not
created; the existing tag is selected instead, and the user is told which tag was used.

**Rationale**: **There is no uniqueness anywhere today** — no unique index on `tags.name`
(`Entities.kt:184-187`), no rule in `TagDomain`, no check in `TagsRepositoryImpl.create`.
This is new logic, and it has to live in the domain so the tags screen and the picker
share it rather than each growing their own copy.

**Case-insensitivity** is the judgement call here: "Invoice" and "invoice" are the same
label to a person, and the repository normalises nothing (no trim, no case folding
anywhere except the write-time `trim()` in `CreateTagUseCase`), so the rule has to be
written down. It is stated in `TagDomain` next to the other name rules.

**Alternatives considered**:
- *Exact-match only* — rejected: it lets two visually identical tags exist, which is the
  outcome the requirement is trying to prevent.
- *A unique index on `tags.name`* — rejected for this feature: it would need a migration
  over existing data that may already contain duplicates, and it is a database-wide policy
  decision rather than part of this feature.

---

## D15 — One row component with a real checkbox, shared by both lists

**Decision**: The public `ChecklistItemRow` becomes the single row widget for checklist
items *and* subtasks. It gains an optional `onOpen` (for opening a subtask as its own
task) and loses its "Convert to task" menu item. The private duplicate inside
`ChecklistEditorSheet.kt:120-163` is deleted.

**Rationale**: There are two checkbox-row house styles in the codebase today — the public
`ChecklistItemRow.kt` (Material3 `Checkbox`, `bodyLarge` text, line-through when done) and
a private near-copy in the checklist sheet. The public one currently has **no production
caller**; its KDoc claims to replace rows in two screens that no longer contain them. The
spec asks for subtask rows and checklist rows that look the same, which is the moment to
have exactly one of them.

**What already exists and does not need building**: the flat section shape is already
there. `ExtraSectionCard` is flat — never collapsible — with an icon, a label and a
content slot, and `SubtasksSection` already has the header-with-count and the inline add
row. The screenshot's layout is this repository's layout.

**What is missing**: every existing row uses a `"✓ "` **text prefix** rather than a
checkbox (`SubtasksSection.kt:147`, `TaskDetailExtraSections.kt:76`), and there is no
checkbox row for subtasks at all.

**The checklist section must always render.** Today it returns early when the list is
empty (`TaskDetailExtraSections.kt:61`), which means an empty task shows no checklist
section and — because the section is the only place the add row could live — **there would
be no way to add the first checklist item from the detail screen**. The section therefore
always renders, like `SubtasksSection`, and its count reads "0/0" when empty. This is
required by the spec's finished-of-total requirement and is the reason the spec's edge
case ("the last checklist item is deleted → the count reads zero of zero") is satisfiable
at all.

---

## D16 — Checklist ordering uses `max(sortOrder) + 1`

**Decision**: `ChecklistRepositoryImpl.addItem` stops using the number of existing rows and
uses the highest existing order plus one.

**Rationale**: The current code uses the row count, and the file's own comment records
that this has already caused a bug once ("Append, not `0`"). A delete followed by an add
collides, and the tie is broken by rowid — an order SQL does not promise. An inline add
row makes adds frequent, which is exactly the usage that finds the collision.

---

## D17 — The server allowlist row is added by a dated migration

**Decision**: `task.workflowState` is added to `config/docs/sync-field-allowlist.json`,
`supabase/sync_field_allowlist_seed.sql` is regenerated from it, and a dated migration in
`supabase/migrations/` inserts the row into the server's `sync_field_allowlist` table.

**Rationale**: The client derives the wire field list from the model, so the field is on
the wire the moment it is on `Task`. Whether the **server** accepts it is a separate
question, answered by the server's `sync_field_allowlist` table. The gate
(`sync allowlist seed matches contract`) fails when the server names a field the client
does not write, but it does **not** fail when a field the client writes is missing from
the server — so the omission is silent, and the field is simply dropped by the server.

**The precedent to avoid**: `task.seriesId` and `project.closedAt` are exactly this debt
today — open issue #1050, "The server's field table is owed task.seriesId and
project.closedAt". The gate is green, the field is on the wire, and the server drops it.
This feature must not add a third.

---

## D18 — Test tags are added only after the catalogue is regenerated

**Decision**: Any new `TestTags` constant is accompanied by a regenerated catalogue.

**Rationale**: A new constant is unusable until the catalogue is regenerated
(`TestTags.kt:20-33`), and `TestTagsCatalogJvmTest` fails on a catalogue that does not
match. The regeneration is
`./gw :shared:jvmTest -PupdateGoldens=true --tests "*TestTagsCatalogJvmTest*"`, followed by
`python3 scripts/check-test-tags-catalog.py --check`.

**Already waiting**: `TestTags.subtaskCheckbox(title)` is declared and has **zero**
usages — it is the tag for exactly the checkbox this feature adds.

---

## D19 — The work is split into six ordered phases

**Decision**: The implementation is six phases, each of which compiles and passes the
gates on its own:

1. **The stored state** — domain enum, Room column, manual migration, backup DTO, sync
   allowlist, server migration.
2. **The marking vocabulary** — `TaskMarking`, the policy, the row and agenda rendering.
3. **The agenda selectors** — the two new variants, the registry, the catalogue, the
   presets, the saved-view decode guard.
4. **The dependency verb** — the picker control, the write path, the widened read path.
5. **The inline items** — the shared checkbox row, the checklist section, the subtask
   section.
6. **The inline tag creation** — the picker create affordance and the duplicate rule.

**Rationale**: Phase 1 is the only phase that touches the database, the backup format and
the server; it is also the phase everything else depends on, because the marking policy
and the state selector need the stored state to exist. Phases 5 and 6 are independent of
1–4 and could be done in either order. The phases are cut this way because each one
produces something a user can see or a gate can check, rather than because the files are
adjacent.

---

## Open questions

None. Every item the spec marked as needing a decision was settled with the user before
Phase 0, and the two judgement calls that remained — the absence of a stored "waiting"
state and the scope of the attached screenshot — are recorded in the spec's Assumptions and
Out of Scope sections.
