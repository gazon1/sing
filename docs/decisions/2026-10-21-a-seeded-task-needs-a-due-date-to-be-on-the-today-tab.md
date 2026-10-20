---
title: "A seeded task needs a due date to be on the Today tab"
date: 2026-10-21
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

A large share of the E2E failures had one shape: the flow seeded a task, then
asserted that the task was visible, and the task was not there.

The seed was not at fault. The task was in the database, under the right user,
with the right title — and the flow still could not see it:

    $ adb shell "run-as com.singularity.todo sh -c 'cat databases/todo.db'"
    SeedProbe2026|          | 0 |  |  | Task | None     ← due_date empty

    $ adb shell uiautomator dump /sdcard/ui.xml   # the app on screen
    text="No tasks"                               ← the screen says it has none

The app's agenda has a **"No Date" bucket**, and it is disjoint from "Today"
(`AgendaPresets`: *"No Date" sits after it but is disjoint (`dueDate == null`)"*).
A task seeded without a due date lands in that bucket, and the Today tab does
not show it. The seed activity has always documented `due=today|tomorrow|…` —
and 22 of the flows seeded a task, 3 of them with `due=`.

The same seed activity had a second silence: a flow asked for
`desc=HasDescription`, and the parameter was not in the list of supported ones,
so the description was never written and the flow filtered for a description
that did not exist.

## Idea

- **A.** Change the app: show undated tasks on the Today tab.
- **B.** Change the flows: say `due=today` when the flow needs the task on the
  Today tab, and make the seed honour `desc=`.
- **C.** Change the seed: always give a seeded task today's date.

## Decision

**B** — the flows say what they need, and the seed honours what it is asked.

- Ten flows that seed a task and then expect to see it now carry `&due=today`.
- The seed activity writes the description when the link carries `desc=`.

**A is not on the table.** "Today" means tasks due today; a task with no date
is not due today, and showing it there would make the bucket mean nothing. The
"no date" section exists precisely to hold those tasks.

**C is not on the table either.** A seed that always dated its tasks would make
"no date" unreachable — a flow could no longer seed an undated task at all, and
the bucket would become unobservable.

## Rationale

- **A flow that seeds a fixture and then asserts on it has to say what makes
  the fixture visible.** The alternative is a fixture that is in the database
  and not on the screen, and a failure that says "element not found" instead
  of "the fixture is in the wrong bucket".
- **The seed's silence was the second half of the defect.** `desc=` was
  accepted and dropped; a parameter that is understood by the flow and ignored
  by the tool is worse than one that is rejected.
- **The comment in the flow is the part that keeps it true.** Each of the ten
  says why `due=today` is there, because the next person to read the link
  would otherwise take it for noise and drop it.

## Consequences

- **The seeded fixtures are visible where the flows expect them.** `03-clear`
  passes after the change; the others moved from "the task is not there" to
  their real assertions.
- **The seed activity grew one parameter**, in `src/debug/` only — it is
  excluded from release APKs.
- **A flow that genuinely needs an undated task still has one**: it simply
  leaves `due=` out, and asserts in the "No Date" section.

## Links

- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt`
  — the `desc` parameter
- `Maestro/flows/attachments/01-add-url.yaml`, `attachments/02-delete.yaml`,
  `attachments/03-empty-url-rejected.yaml`, `attachments/05-renders-correctly.yaml`
- `Maestro/flows/search/02-cyrillic.yaml`, `search/03-clear.yaml`,
  `search/04-filter-description.yaml`, `search/05-filter-pinned.yaml`,
  `search/07-saved-search.yaml`
- `Maestro/flows/tasks/subtasks/TASK-SUB-01.yaml`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
  — the buckets and what they hold
- `2026-10-20-maestros-schema-is-the-contract.md` — the other reason the flows
  were not measuring anything
