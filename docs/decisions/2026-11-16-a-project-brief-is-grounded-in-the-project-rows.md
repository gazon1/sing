---
title: "A project brief is grounded in the project's rows, and the review stays one tool"
date: 2026-11-16
status: accepted
tags: [ai, projects, prompts, planning]
---

# A project brief is grounded in the project's rows, and the review stays one tool

## Context

`project_review` reviewed a project by name. The caller passed a project name and a
list of task titles — titles it had made up, or that the app had resolved from a global
selection. Nothing tied those titles to the named project, and the tool never asked the
app what the project actually contains: how many tasks it has, how many are overdue,
blocked or stagnant, what its tasks are waiting on, what its notes say.

The facts were available. `ProjectId` exists, the projects and tasks repositories read
them, and `HealthRules`, `TaskSelectionResolver` and `TaskPlanSupport.ORDER` already own
the meaning of "task", "health" and "order". A brief that re-decided any of them would be
a second interpretation of the same rows, and the two would drift.

Notes are the awkward part: a note links to a task through `Note.taskId`, and there is no
note-to-project relation. The brief needs the notes of a project's tasks without reading
them one task at a time.

## Idea

1. **A second tool, `project_brief`, beside `project_review`.** Clean naming, but two
   tools, two prompts and two places where the model's view of a project is defined.
2. **A generic `AiContextBuilder` that assembles context for any tool.** A framework
   around one call site, and the issue explicitly excluded it.
3. **One tool with an optional `projectId`: the same review path, grounded when the
   caller knows the project.**

## Decision

Option 3. `ProjectReviewInput` gains an optional `projectId`; `projectName` keeps its
default of `""` and the old callers — a name and titles, or a global `TaskSelection` —
are untouched. When the id names a project, the tool builds a `ProjectBrief` and sends
that instead of the titles; when it names no project, the tool says so and sends no
prompt at all, because a review of a name the app cannot see would be about nothing.

The brief is built by one internal component, `ProjectBriefSupport`, beside the tool
that uses it. It reads the project's row, the whole task list, and the notes of the
project's tasks in one batch query, and it reuses `HealthRules` with the user's stored
staleness threshold and the shared selector context, so "stagnant" means the same set
here as on the agenda and in `list_tasks`.

**Health and blockers are decided against the whole task list, and membership is
applied afterwards.** A task blocked by a prerequisite in another project is blocked,
and a brief that had only the project's own tasks would call it fine. The same task
appears in the brief as a *blocker* — id and title — and never as a member: a
dependency is not work the project owns.

Counts are taken before anything is cut: the totals and the health counts cover every
task of the project, while the page carries at most fifty tasks and twenty notes. Each
bounded collection states what was left out, so a sample cannot read as the whole
project. Ordering is `TaskPlanSupport.ORDER`, the one the planner and the list tool
use, so truncation is deterministic.

The notes arrive as titles and short excerpts, not as a corpus: a brief is a summary of
the project, and note bodies are not context a review needs in full. `NotesRepository`
gained one batch read (`watchForTasks`) and `NoteDao` the query behind it. No
`Note.projectId` column, no migration, no note-per-task watcher.

The brief reaches the user through the Project Detail screen: an entry in the existing
top-bar overflow menu, a sheet that shows it, and a `StateFlow` of states on
`ProjectDetailViewModel`. No new top-level destination, no second review tool, and no
write path — the tool has none, so a suggestion cannot become a mutation.

## Rationale

One tool, two ways in, is the smallest change that fully answers the issue. The MCP
surface, the Koog registry and the exported tool description all read the same tool, so
adding an optional field is additive everywhere; the exported surface changes by one
description line.

The facts belong in a small component next to the tool, not in a framework. A generic
builder would be a second thing to learn for a second caller that does not exist.

Reading the whole task list is not an optimisation mistake; it is the correctness
condition. Health and blockers are properties of a task *in the context of every other
task*, so a subset would quietly produce a different answer than the agenda does. The
full snapshot is what the issue's "same health rules and selector context" requires.

Stating the omitted counts is the difference between "these are your tasks" and
"these are some of your tasks". The planner learned the same lesson in the same week.

The project brief is a projection of the app's rows, and the model explains them. The
prompt says so: the facts are the application's own, and the model may read them and
say what they mean, but may not invent a goal, a deadline, progress or a blocker that
the facts do not carry.

## Consequences

- `project_review` keeps its name, its output and its old callers; with a `projectId`
  it answers about a real project.
- A brief of a deleted project answers "No project with id … exists." — explicitly,
  instead of reviewing a name.
- The prompt gained one paragraph about facts versus invention, and a user prompt for
  the brief.
- `NotesRepository` and `NoteDao` carry one batch read each, and the fake counts the
  calls so a test can assert that a project's notes are read once.
- The brief is bounded by the page it carries; a project with fifty-five tasks states
  that five were left out.
- The Project Detail screen gained an action and a sheet, not a destination.
- Koog is wired on both platforms, so the brief is available on Android and Desktop;
  nothing about the feature is platform-limited.

## Links

- Issue: `#2979` — extend Project Review with project-scoped context
- `#2978` / `docs/decisions/2026-11-16-a-plan-is-grounded-in-task-facts.md` — the same
  grounding for the daily planner
- `#2989` / `docs/decisions/2026-11-15-task-health-has-one-classifier.md` — the health
  and threshold owners the brief reads through
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ProjectBriefSupport.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ProjectReviewTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/prompts/Prompts.kt`
