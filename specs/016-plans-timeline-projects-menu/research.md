# Research: Plans Timeline and Projects Menu

**Feature**: `specs/016-plans-timeline-projects-menu`
**Date**: 2026-10-26

Everything below was verified in the working tree at `39fd044a` before it was written down. A
decision that rests on a fact is stated with the file it came from, so a later reader can check it
rather than trust it.

---

## The facts the design rests on

### What the Plans tab is today

- `AppDestination.Plans` is a top-level `data object` with the title "Plans"
  (`shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt:52`).
- `DestinationKind.tabs` lists six destinations: the three agenda routes, `Plans`, `Pomodoro` and
  `Calendar` (`AppDestination.kt:422`).
- The tab renders the project list: `ProjectsEntries.kt:23` binds `AppDestination.Plans` to
  `ProjectsNavGraph(ProjectsRoute.List)`.
- The FAB on that tab says "Add project" and opens the project editor
  (`shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt:73`).
- `AppDestination.Plans` is classified into `ScreenFamily.Projects`; the sealed family list has no
  `Plans` member of its own (`ScreenFamily.kt:15`).

### What the agenda already knows about dates

- `RelativeBucket` carries `Today`, `Yesterday`, `Tomorrow`, `ThisWeek`, `NextWeek`, `ThisMonth`,
  `NextMonth`, `Overdue` and `NoDate`, and each bucket knows its own date range
  (`shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/RelativeBucket.kt`).
  The week starts on Monday — the range is computed from `dayOfWeek.ordinal`, where Monday is 0.
- `AgendaDefinition` is a **fixed, ordered list of sections**, and each section carries exactly one
  `Selector` (`feature/agenda/domain/model/AgendaDefinition.kt`). `AgendaEvaluator` walks that list
  in order and collects the matching tasks, honouring the `discard` flag.
- The Overdue bucket excludes completed tasks; the other buckets do not. A completed task due today
  is rendered by the agenda's "Today" section.

### What the data layer can already ask for

- `TaskFilter.ByDateRange(from, to)` exists and maps to `watchByDateRange`
  (`feature/tasks/domain/model/Task.kt:63`).
- The query is `WHERE user_id = :userId AND archived_at IS NULL AND due_date >= :from AND
  due_date <= :to ORDER BY due_date ASC, is_pinned DESC` — it excludes trashed tasks, and it does
  **not** exclude completed ones.
- `Task.dueDate` is the date the timeline groups on; `startDate` and `endDate` are separate fields
  for tasks that span a range.

### Where the menu lives

- The Android bottom sheet builds its own private `MenuSections` list
  (`shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt:150`). Its KDoc
  claims that appending to `DestinationKind.menuEntries` makes an entry appear automatically — that
  is **not** what the code does: the sheet and the Desktop drawer read different lists.
- The Desktop drawer renders `DestinationKind.tabs` and then `DestinationKind.menuEntries`
  (`shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt:104`), and the Desktop
  "View" menu is built from the same tab list.
- `MenuItem` requires a `destination: AppDestination`. A project in a tree is not a destination,
  so the menu model cannot hold one as it stands.
- The project model already carries the hierarchy: `Project.parentId: ProjectId?`, plus
  `closedAt`, `isDeleted`, `sortOrder`, `colour` and `icon`
  (`feature/projects/domain/model/Project.kt`).
- `ProjectsRepository` exposes `observeProjectsWithCounts()`, `observeChildrenOf(parentId)`,
  `observeByParent(parentId)` and `observeClosed()`.

### The hazards already documented in this repository

- `.agents/skills/singularity-todo-agenda-section-design/SKILL.md` records the failure this feature
  walks straight into: a task that lands in two sections is rendered twice, and a `LazyColumn` keyed
  on the task id alone **crashes** with `Key "task-id" was already used`. The skill's rule is that a
  row key must be `"{section.name}/{task.id.value}"`.
- The skill also records the discipline a bucket needs `discard = true` for. The timeline's
  sections do not need the flag, because they are disjoint by construction — but that has to be a
  property of the data, not a hope.

### What the traceability machinery expects

- A spec may declare a scenario matrix; `traceability scenarios --generate` turns each declaration
  into a YAML file under `scripts/src/traceability/scenarios/<area>/`, and `--check` fails when a
  declaration has no file.
- The area must be `feature.<x>` or `core.<x>`, and the id's first segment must be a prefix of the
  area's directory name.
- A scenario with a claimed target and no carrier is a **hole**, and
  `scripts/check-traceability-ratchet.py` fails when the hole count grows. The floor lives in
  `config/docs/traceability-ratchet.json` and is raised by hand, with the reason written next to it.
- A carrier is a `@DisplayName` in a user-flow test source set, or a `scenario:` tag on a Maestro
  flow. `shared/src/jvmTest` is not a user-flow source set and cannot carry a scenario.

---

## Decisions

### D-01 — The timeline is a new feature, not a new agenda preset

**Decision**: `feature/plans` is a new module with its own domain, data and presentation layers.

**Rationale**: The agenda's screen is built around a *saved* definition — a named list of sections
the user can store, edit and reorder, with a dropdown to switch between them. The timeline has no
saved views, no section editor and no selector composer; it has a granularity the user switches and
a date range that is recomputed every day. Modelling it as a saved agenda view would put a
per-user, per-device artefact in the middle of what is a function of the calendar.

**Alternatives considered**:
- *A new `AgendaPresets.Timeline` definition.* Rejected: the definition is a fixed list, and the
  timeline's sections are a function of `today`. A 30-day preset would be 30 static sections whose
  labels go stale the next morning, and the "Today" section would have to be a `RelativeBucket`
  while every other day would be a hard-coded `DateRange` — two different kinds of section in one
  list, which is exactly what the discard rules in the skill exist to keep straight.
- *The timeline inside the agenda feature, next to the agenda.* Rejected for the same reason the
  agenda itself was split out of the task list: it is a different screen with different chrome.

### D-02 — The timeline's sections are generated, and each task lands in exactly one by construction

**Decision**: The timeline builds its periods from the granularity and `today`, and assigns each
task to the single period that contains its due date. The periods are disjoint.

**Rationale**: The crash this feature could cause is a task rendered twice, and the repository has
already paid for that bug once. Disjoint periods make the invariant structural: there is no
`discard` flag to get wrong, because there is nothing to discard. The renderer still keys rows as
`"{section.id}/{task.id}"`, so a future mistake renders a duplicate row instead of crashing the
screen — the defence the skill prescribes.

**Alternatives considered**:
- *Expressing the periods as `Selector.DateRange` sections and reusing `AgendaEvaluator`.* Tempting
  — the evaluator would do the assignment for free — but the evaluator's cost is one selector match
  per section per task, and a month-granularity timeline has 12 sections against a task list the
  agenda already loads whole. Owning the grouping also owns the horizon, which the agenda's
  evaluator has no concept of.

### D-03 — The row model is shared, not copied

**Decision**: the timeline's row is `TimelineTask`, and the vocabulary it carries — the markings —
is the tasks feature's, computed by the shared policy (`computeTaskMarkings`). Both screens use it.

**Rationale**: the markings are a vocabulary — blocked, follow-up, next up, in progress, overdue,
no date, pinned, repeating, completed. Two copies would be two vocabularies that drift, and what a
user sees on a row would depend on which screen they happened to be on.

**What changed while the work was in flight**: this decision was first written for a single badge
(`AgendaBadge`, six cases) that this change promoted out of the agenda feature. The upstream work
that landed in `origin/main` on 2026-10-26 replaced the badge with the markings — a set, and a
superset of the badge's cases — and moved the policy into the tasks feature on the way. The
timeline was adapted to the markings rather than keeping the badge alive: `TimelineTask` carries
the markings the agenda shows, and the timeline's grouper asks the same policy the agenda's
evaluator asks. The decision did not change; the vocabulary it names did.

**Alternatives considered**:
- *The timeline imports the agenda's types directly.* Allowed by the architecture rules, and
  cross-feature imports are common in this codebase (`feature.agenda` imports `feature.tasks` in 54
  places). Rejected because the names would keep saying "Agenda" on a screen that is not the
  agenda, and because the next feature that wants a task row would make the same choice again.
- *The timeline writes its own row and its own markings.* Rejected: two vocabularies.

### D-04 — The timeline shows open work only

**Decision**: A completed task is not on the timeline.

**Rationale**: The screen answers "what is still ahead". A task the user finished yesterday is not
ahead of anything, and the agenda's own buckets do not make this distinction for the dated buckets
— which is why the timeline makes it explicitly rather than inheriting it.

**Alternatives considered**:
- *Show completed tasks, as the agenda's "Today" section does.* Rejected: the timeline is a forward
  view, and a list of finished work reads as work still to do.
- *Show completed tasks, struck through, as some list views do.* Rejected: the timeline is already
  a long list; adding a second visual state per row buys nothing here.

### D-05 — The horizon is a property of the granularity, and it bounds what is *shown*

**Decision**: Day granularity covers the next 30 days, week granularity the next 12 weeks and month
granularity the next 12 months. The timeline reads the whole task list and the horizon decides what
of it the sections hold.

**Rationale**: The horizon is what "upcoming" means, and it is a property of the *display*, not of
the query. It was first written as two range queries — the overdue pool and the horizon — and the
implementation showed that to be wrong: `Blocking.blockedTaskIds` treats a prerequisite that is
missing from the loaded set as *not blocking*. A range query would therefore show a task as
unblocked that the agenda shows as blocked, and the same task would be blocked in one screen and
free in the other. Loading the whole list keeps the two screens telling the same story; the horizon
still decides what the user sees.

**Alternatives considered**:
- *Two range queries, one for the overdue pool and one for the horizon.* Rejected: it loads a
  partial set, and a partial set makes the blocking rule answer a different question than the same
  rule answers elsewhere.
- *One wide query from the epoch to the horizon end.* Rejected: it fetches the future beyond the
  horizon and throws it away, which is exactly the part that grows.
- *Load every task and group in memory, the way the agenda does.* Kept, for the reason above: the
  agenda's sections can select on anything — status, priority, tag, project — and the timeline
  groups on one field, but a task's blocked state is not a field.

### D-06 — The granularity is a per-profile preference

**Decision**: The chosen granularity is stored through a feature-scoped settings repository with
its own namespace, and restored when Plans is opened.

**Rationale**: `DefaultAgendaViewSettingsRepository` is the existing precedent for a preference
that belongs to one feature rather than to the whole app: a port in the feature's domain, a
DataStore implementation, and a namespace so two features cannot collide. The user chose week,
closes the app and comes back — the choice has to be there.

**Alternatives considered**:
- *Keep it in the ViewModel only.* Rejected: it would reset on every process death.
- *Put it in the global settings bundle.* Rejected: it is not a global preference, it is a view
  setting of one screen, and the global bundle is where the app's real settings live.

### D-07 — The Calendar leaves the tab list and joins the menu list

**Decision**: `AppDestination.Calendar` is removed from `DestinationKind.tabs` and added to
`DestinationKind.menuEntries`. The Android sheet's own `MenuSections` list gains a Calendar entry
as well.

**Rationale**: The user named the menu as the way in, on both platforms. The Desktop drawer already
reads `menuEntries`, so the Desktop half is one list edit; the Android sheet has its own list, so
it needs its own edit. Removing Calendar from `tabs` also removes it from the Desktop "View" menu
and from the drawer's tab group, which is what "no longer a tab" means.

**Alternatives considered**:
- *Keep the tab and add a menu entry.* Rejected: the request was to remove the button.
- *Leave the Calendar screen reachable only by deep link.* Rejected: a screen nobody can reach is a
  screen nobody uses.

### D-08 — The menu gains a kind of entry that is not a destination

**Decision**: The menu model becomes a sealed hierarchy: a *link* entry (what exists today) and a
*projects* entry that renders the tree. Both platforms render the same tree composable.

**Rationale**: `MenuItem` requires a `destination: AppDestination`, and a project in a tree is
not a destination — it opens a detail screen, not a tab. And the two menus are drawn by different
code, so a tree implemented twice would be two trees that drift. One shared composable, used by
the sheet and the drawer, is what makes "the same menu on both platforms" true by construction
rather than by review.

**Alternatives considered**:
- *A tree rendered inside the sheet and another inside the drawer.* Rejected: two implementations
  of one feature, and the parity requirement would depend on a human noticing.
- *Turning every project into an `AppDestination`.* Rejected: that puts project ids into the
  navigation key space, where the destination set is a closed, serialisable type.

### D-09 — The tree is built with the repository's own depth-first traversal

**Decision**: The tree is assembled with `core/tree/traverseDepthFirst`, which detects cycles.

**Rationale**: The utility already exists, it already knows how to walk a parent/child structure,
and it throws on a cycle rather than looping forever. Project hierarchies are edited by hand, so a
cycle is a user mistake the code must survive.

### D-10 — The full project list stays reachable

**Decision**: The menu's Projects section carries the tree and, with it, an entry into the existing
full project list.

**Rationale**: The list does things the tree does not: it searches across projects, it sorts them,
and it manages the closed ones. Moving the list out of the tab bar must not remove those.

**Alternatives considered**:
- *Deleting the list screen.* Rejected: search, sorting and closed-project management have no
  other home, and the request was to move the projects into the menu, not to remove the ability to
  work with them.

### D-11 — The work lands in two parts, timeline first

**Decision**: Part A is the timeline; Part B is the menu — the tree, the Calendar's move, and the
removal of the project list from the Plans tab.

**Rationale**: In the other order the Plans tab would be the timeline with the project list
stranded, because the list's only entry point is the tab. With the timeline first, the project list
keeps a home through a plain menu entry, and Part B upgrades that entry into the tree. Each part is
a coherent app on its own.

### D-12 — The FAB on the Plans tab creates a task, not a project

**Decision**: The Plans tab's FAB says "Add task" and prefills the due date with today, the way the
agenda's "Today" tab does. Each section header carries its own add control, which prefills that
section's date.

**Rationale**: The tab is a list of tasks now. Leaving an "Add project" button on it would be a
button for something the screen no longer shows. The agenda already does exactly this split — a
tab-level FAB and a per-section add — and the task editor already accepts a prefilled due date
through `TasksStartRoute.Create(today)`.

### D-13 — The scenarios are declared now and carried by the implementation

**Decision**: The eighteen scenarios are declared in the spec and their YAML files exist, with the
behaviour each one asserts written out. Their carriers — the tests and Maestro flows — land with
the implementation that makes them true.

**Rationale**: A declared scenario with no file is a broken declaration and the binding check
fails. A file with a `TODO` body fails validation. The third option, which is what this repo does,
is to write the behaviour now and let the implementation land the automation: the scenario files
are the specification of what the screen must do, and the carriers are the proof.

**Consequence, stated plainly**: eighteen scenarios with no carrier are eighteen holes, and the
ratchet fails when holes grow. The floor in `config/docs/traceability-ratchet.json` is therefore
raised in this change, with the reason written into the file, and every one of those holes is a
hole the implementation must close.

---

## Open questions

None. Every decision the specification left open was either answered by the user before the
specification was written, or is settled above with the file that settles it.
