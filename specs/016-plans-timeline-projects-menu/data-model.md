# Data Model: Plans Timeline and Projects Menu

**Feature**: `specs/016-plans-timeline-projects-menu`
**Date**: 2026-10-26

The entities below are the ones this change introduces or moves. Everything else it reads — the
task, the project — already exists and is described by the code that owns it.

---

## PlansGranularity

The size of one timeline period, and the horizon that goes with it.

| Field | Type | Meaning |
|---|---|---|
| `Day` | — | One calendar day per section |
| `Week` | — | One Monday-to-Sunday week per section |
| `Month` | — | One calendar month per section |

Each case carries the number of periods the timeline shows: 30 days, 12 weeks, 12 months. The
number of periods, not their length, is what the user reads as "how far ahead this goes".

**Validation**

- The granularity is one of the three cases. There is no "custom" and no "year" in this change; the
  set is open, and the year level is a later addition.
- The stored value is always one of the three cases. A stored value that cannot be read back falls
  back to `Day` rather than leaving the screen without a grouping.

**Relationships**

- One granularity drives one list of periods, and through it one set of sections.
- The granularity is persisted per profile.

---

## TimelinePeriod

One span of time that a section covers.

| Field | Type | Meaning |
|---|---|---|
| `id` | `String` | Stable key, unique within a timeline: `day:2026-10-26`, `week:2026-10-20`, `month:2026-11` |
| `start` | `LocalDate` | First day of the period, inclusive |
| `end` | `LocalDate` | Last day of the period, inclusive |

**Validation**

- `start <= end`. A period never runs backwards.
- For a day, `start == end`.
- For a week, `start` is a Monday and `end` is the Sunday six days later.
- For a month, `start` is the first of the month and `end` is its last day.
- The periods of one granularity are pairwise disjoint and sorted by `start`. This is the property
  that makes "a task is in exactly one section" structural rather than a rule to remember: a task
  with a due date can fall inside at most one of them.

**State transitions**

None. A period is derived from the granularity and today's date, and it is recomputed when either
changes. It is never stored.

---

## TimelineSection

A period, its label, and the tasks that belong to it.

| Field | Type | Meaning |
|---|---|---|
| `id` | `String` | The period's id — the section's key |
| `label` | `String` | What the header says: `Today`, `Tomorrow`, `Fri, 12 Sep`, `12–18 Sep`, `September 2026` |
| `period` | `TimelinePeriod` | The span the section covers |
| `tasks` | `List<TaskRowItem>` | The open tasks due inside the period, in the order they are shown |

**Validation**

- Section ids are unique within a timeline. Two sections with one id would key the list wrongly.
- A task appears in at most one section across the whole timeline.
- A section with no tasks is not rendered. The timeline shows the periods that have work in them,
  in chronological order; a gap in the calendar is not a section.
- The label names the period. The header is the only thing that tells the reader which period the
  rows under it belong to.
- The overdue section, when present, is first. It has no period — overdue work belongs to no future
  day — and it holds the tasks whose due date is before today.
- A completed task is in no section.

**State transitions**

- The granularity changes → the sections are rebuilt from the same tasks under new periods.
- A task's due date changes → the task moves between sections.
- A task is completed → the task leaves its section.

---

## TimelineTask and the markings

The row the timeline draws, and the vocabulary it shares with the agenda.

The vocabulary is the **tasks feature's** — a set of markings, computed by one policy
(`computeTaskMarkings`), and the timeline asks that policy rather than deciding anything itself.
While this change was being written, the vocabulary was a single badge owned by the agenda; the
upstream work that landed in `origin/main` on 2026-10-26 replaced it with the markings
(`TaskMarking`), which is a superset: a task that is both blocked and overdue shows both.

| Field | Type | Meaning |
|---|---|---|
| `task` | `Task` | The underlying task |
| `markings` | `ImmutableSet<TaskMarking>` | What the task is marked with: blocked, follow-up, next up, in progress, overdue, no date, pinned, repeating, completed |
| `seriesDates` | `Int?` | How many dates the task's series has; null when it stands alone |

**Validation**

- The markings are a set, not a single value: a task can carry several, and the screen renders all
  of them.
- The markings are computed, never assigned by a screen: one policy decides what a task is marked
  with, and both the agenda and the timeline call it.

**Relationships**

- One row wraps one task. A task appears once per section that holds it — and, on the timeline,
  exactly once.

---

## ProjectTreeNode

A project in the menu's tree.

| Field | Type | Meaning |
|---|---|---|
| `project` | `Project` | The project itself |
| `children` | `List<ProjectTreeNode>` | The projects nested under it |
| `depth` | `Int` | How deep the node sits, for indentation |

**Validation**

- A node is never its own ancestor. The hierarchy is edited by hand, so a cycle is a user mistake
  the code has to survive rather than loop over — the tree is built with the traversal that detects
  cycles.
- A project whose parent no longer exists appears at the top level. It is not dropped.
- A closed project is in no tree. Closed projects are managed from the full project list.
- Roots are the projects with no parent; every other project is a child of exactly one.

**State transitions**

- A project is created → it appears under its parent, or at the top level.
- A project is renamed → the node shows the new name.
- A project is moved under another project → it leaves one subtree and joins another.
- A project is deleted → it leaves the tree.
- A node is expanded or collapsed → the node's children are shown or hidden. Expansion is a view
  state and is not persisted.

---

## PlansSettings

The one preference this feature owns.

| Field | Type | Meaning |
|---|---|---|
| `granularity` | `PlansGranularity` | How the timeline is grouped; `Day` when nothing has been chosen |

**Validation**

- Reading a stored value that is not one of the three cases yields `Day`.
- The value is stored per profile, in the feature's own namespace, so it cannot collide with
  another feature's preferences.

---

## What this change does not model

- **A saved timeline view.** The timeline's sections are a function of the calendar, not something
  the user stores and reopens. There is no timeline definition to persist.
- **A calendar event.** The Calendar screen is unchanged and owns its own model.
- **A task's date range.** A task may have a start and an end date; the timeline groups by the due
  date, and the other two fields are not part of this change.
