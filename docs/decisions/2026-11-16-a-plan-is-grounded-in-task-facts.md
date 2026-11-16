---
title: "A plan is grounded in task facts, and the evening only reports what was stored"
date: 2026-11-16
status: accepted
tags: [ai, tasks, planning, prompts]
---

# A plan is grounded in task facts, and the evening only reports what was stored

## Context

`weekly_plan` has always been a daily planner wearing a weekly name: its prompt asks
for 3-5 focus tasks, a plan that fits, and buffer. What it did not have was facts. It
received task titles — resolved from the app's selection or made up by the model — and
a title cannot say that a task is overdue, blocked, due at 17:00, estimated at ninety
minutes, or waiting on someone. So the model ranked by the sound of the words.

The app already knows those facts. `TaskSelectionResolver` decides membership,
`HealthRules` decides the bucket, and `#2989` made both the single owners of their
questions. `list_tasks` already returns them, but as a public DTO for a reader, not as
a prompt contract.

The evening half had its own limit. There is no persisted daily plan, and `completedAt`
is the only stored fact about a finished task. Anything else — how long the work took,
what the morning planned — would have to be invented.

## Idea

1. **Persist a daily plan and review it in the evening.** Gives the review something
   to compare against, and adds a table, a write path, a migration and a second source
   of truth about the day.
2. **Hand `ListTasksTool`'s output to the planner.** Reuses a DTO, but couples the
   prompt to a public read contract that exists for another reader — pagination
   cursors, full cards, cursor warnings and all.
3. **A small AI-specific projection at the tool boundary, built from the same selector
   and health rules, and a `mode` on the existing tool.**

## Decision

Option 3. `TaskPlanSupport.facts` reads the live tasks once, resolves the selection
through `TaskSelectionResolver`, orders with the same comparator `list_tasks` uses
(now `TaskPlanSupport.ORDER`), caps the page at 50 and reports the remainder as
`total`. Each task becomes a `PlanTask`: id, title, due date and time, priority,
estimate, health bucket, blockers (id and title, so two tasks with the same title stay
distinguishable), project id, and `completedAt` when there is one. Membership and
health are not re-decided at the prompt boundary. `select` — what `project_review` and
the legacy titles path use — delegates to the same facts, so the two plan paths cannot
resolve different sets.

`weekly_plan` keeps its name and gains `mode: morning|evening`, defaulting to morning.
The output keeps `items` — the focus titles, in order — and adds `sections` and
`firstAction`, so an old caller that reads `items` is unaffected. `mode` is additive
for the same reason: the MCP surface, the Koog registry and the exported tool
description read the same tool.

The evening review is bounded by storage. "Completed today" is a task whose
`completedAt` falls on today in the user's zone, and that is the only completion fact
the tool sends. Both the open page and the completed page are capped at the same
limit, and each states how many were left out. An evening call without a selection
defaults to `active_not_deferred`: a review that knew only completions could not name
the overdue, blocked or still-relevant tasks it exists to name. The prompt states that
there is no saved morning plan to compare against and that carry-over is advice; the
tool has no write path, so a suggestion cannot become a mutation.

## Rationale

The prompt is a contract with a model, and a model reads a small, stable projection
better than a public read DTO built for pagination. Keeping the projection beside the
tool keeps it honest: it is the only shape the planner can see.

Bounding the page and stating the omitted count is the difference between "these are
your tasks" and "these are some of your tasks". The old `select` appended a
`…and N more` marker for the same reason; the structured path carries a count instead.

Sharing `ORDER` is not cosmetic. Truncation needs an order with no ties, and two
orders between the list tool and the planner would let the same selection truncate
differently in two answers.

Not persisting the plan is a deliberate limit, not a gap: a daily report table is a
new storage contract, a migration and a sync surface, and the issue that asked for
this work excluded it. The evening review says less rather than pretending to remember
more.

## Consequences

- The model receives grounded facts; a missing estimate stays missing, and health is
  described, not reclassified.
- `weekly_plan`'s input and output are additive: old callers keep working, and the
  tool-surface export changes only by the new optional field and the description.
- `project_review` and the titles path share the planner's selection and ordering.
- No daily plan is stored, so the evening review cannot claim what the morning
  planned. If that becomes worth doing, it is a separate decision with its own table.
- The Android `PromptExecutor` is implemented now (`AndroidPromptExecutorPort`), so the
  issue's "unsupported on Android" premise no longer holds; nothing hides the feature.

## Links

- Issue: `#2978` — extend existing daily planning with Morning Briefing and Evening Review
- `#2989` / `docs/decisions/2026-11-15-task-health-has-one-classifier.md` — the health
  and threshold owners the planner now reads through
- `docs/decisions/2026-10-22-the-read-surface.md` — the read surface `list_tasks` exposes
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/TaskToolSupport.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/WeeklyPlanTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/prompts/Prompts.kt`
