---
title: The dependency graph has two projections, and they are named apart
date: 2026-10-23
status: accepted
tags: [tasks, dependencies, domain]
---

# The dependency graph has two projections, and they are named apart

## Context

The `task_dependencies` table holds every relation between two tasks, and the
`verb` column says what the relation means: `BLOCKS` (the task waits for the
other one), `FOLLOWS_UP`, `DUPLICATES`, `FIXES`, `SUPERSEDES`.

Two rules read that table, and they read different things:

- **the close rule** asks "is this task waiting for something?" — only the
  `BLOCKS` edges make a task wait, and only they refuse a close;
- **the cycle rule** asks "is the order of the work consistent?" — every edge
  takes part in it, because a relation that does not make a task wait still
  orders the work, and two relations that point at each other are a cycle of
  order even when neither of them blocks anything.

Until now the two projections were one set of methods with two names that did
not say which was which:

| method | what it returned |
|---|---|
| `getDependencyIdsForUser` | every edge — the ordering graph |
| `getBlockingTaskIdsForUser` | the tasks that wait for this one — a third question |
| `blockingDependencyIdsForUser` (in `TaskExtras`) | the `BLOCKS` edges — the blocking graph |

The names were the only thing that distinguished them, and the names were
about the *table*, not about the *question*. A reader who reached for
`getDependencyIdsForUser` while asking the blocking question got every edge
instead of the blocking ones — and a reader who reached for
`getBlockingTaskIdsForUser` got the reverse direction of an edge, which is not
either of the two questions at all.

The words had the same drift. The cycle analysis reported the edges in a cycle
as `blockers`, in `DependencyAnalysis.blockers` — while the close rule used
"blocker" for an open prerequisite. Two different things, one word, in one
feature.

## Idea

Name the two projections after the questions they answer, delete the methods
nobody asked a question with, and give the cycle rule its own word.

## Decision

1. **Two projections, two names, both in the DAO.**
   - `getOrderingEdgeIdsForUser(taskId, userId)` — every edge: the ordering
     graph, where a cycle is looked for.
   - `getBlockingEdgeIdsForUser(taskId, userId, verb)` — the edges with that
     verb: the blocking graph, the one the close rule reads.
2. **The methods that asked neither question are gone.**
   `getDependencyIdsForTask`, `getBlockingTaskIdsForTask` and
   `getBlockingTaskIdsForUser` had no caller outside a test; the first two
   answered the ordering question without a user scope, the third answered the
   reverse direction, which nothing asked for.
3. **A cycle edge is not a blocker.** `DependencyAnalysis.blockers` is
   `DependencyAnalysis.cycleEdges`. The word "blocker" belongs to the close
   rule alone, and the close rule is the one that decides whether a task may be
   closed.
4. **The rule of the close stays where it was.** `Blocking` still owns the
   question "does this task wait for something?", and the DAO projection it
   reads is named after that question.

## Rationale

- **A name that does not say which graph it reads is how the two got confused.**
  The projections were not wrong; they were unnamed. `getDependencyIdsForUser`
  was correct for the cycle analysis and wrong for the close rule, and the
  difference was only visible to someone who read the body.
- **The difference between the graphs is not an accident.** Two tasks that
  "follow up on" each other are in a cycle of order, and neither of them is
  waiting for the other. Collapsing the two projections would either report a
  task as blocked when nothing holds it, or miss a cycle — and the first of
  those is a refusal the user cannot act on.
- **The scope came with the name.** The unscoped reads were the ones nobody
  used, and the two that survived are scoped by the owning user, so a
  cross-user leak is not available through them.
- **The close rule did not move.** The consolidation of the blocking rule
  (`2026-10-22-the-blocking-rule-has-one-owner`) gave the rule one owner;
  changing where it lives now would undo that for a naming problem.

## Consequences

- The DAO has one place that answers each question, and both places say which
  question they answer.
- `DependencyGraphProjectionsTest` pins the difference: the ordering graph
  holds every edge, the blocking graph holds the `BLOCKS` edge only, and a
  relation that only follows up is in the cycle and not among the blockers.
- A reader who adds a third question about the graph has to add a third
  projection, with a name that says which question it is. That is the point.

## Links

- `docs/decisions/2026-10-22-the-blocking-rule-has-one-owner.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/DependencyValidatorImpl.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/domain/logic/DependencyGraphProjectionsTest.kt`
