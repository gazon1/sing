---
title: A closed project is reachable, and the rule that opens it lives in the task feature
date: 2026-10-23
status: accepted
tags: [projects, tasks, lifecycle]
---

# A closed project is reachable, and the rule that opens it lives in the task feature

## Context

Closing a project was added with the blocked-completion guard
(`2026-10-21-blocked-completion-guard`): a project whose work is finished
leaves the project list, and its tasks are closed with it.

Two things followed from that, and neither was decided at the time.

**A closed project had nowhere to go.** `ProjectDao.watchAll` filters
`closed_at IS NULL`, so a closed project left the list. The only way back was
through one of its tasks: reopening a task reopens its project — the rule that
`TaskReopenUseCase` implements. But a project with no tasks has no task to
reopen, so closing it was permanent. A user who closed the wrong project, or
closed one before it was finished, had no way to undo it.

**The rule that opens a project lived in the task feature.**
`TaskReopenUseCase` lives in `feature/tasks` and reaches into
`feature/projects` through `ProjectsRepository`. It is the mirror of
`CloseProjectUseCase`, which lives in `feature/projects` and reaches into
`feature/tasks` through `TaskRepository`. The symmetry is real, and so is the
awkwardness: the feature boundary is crossed in both directions, and the rule
that says "a task that comes back to work brings its project with it" is
owned by the feature that happens to be the one the user touched.

## Idea

Give a closed project a list of its own and a way back that does not depend on
having a task, and decide where the rule that opens a project lives.

## Decision

1. **A closed project has a list.** `ProjectDao.watchClosedForUser` and
   `ProjectsRepository.observeClosed` read the projects the user closed, most
   recently closed first. The projects screen shows them in a section of their
   own, below the open ones — and the section is shown even when there are no
   open projects, because "the list is empty" and "the account is empty" are
   different screens.
2. **The way back is a button.** A closed project is not a card in the project
   list: it is a row with its name and an open-lock button. The card is
   replaced by that row, because a card that navigates somewhere the user did
   not ask to go is the wrong affordance for a project that is not in the list.
3. **`ProjectsRepository.reopen` is the way back, and the rule that calls it
   stays in the task feature.** `TaskReopenUseCase` keeps its place in
   `feature/tasks`, and the projects feature calls the repository directly for
   the explicit "open this project" action.

## Rationale

- **A closed project is finished, not gone, and "finished" has to be
  reachable.** The row is the difference between "the project is closed" and
  "the project does not exist". A user who closes a project by mistake and
  cannot find it again has lost data through a normal action.
- **The section is shown in the empty state too.** If the user closed their
  only project, the screen that says "No projects yet" without mentioning the
  closed one is a screen that hides the way back exactly when it is needed.
- **The rule stays where the user's action is.** The reason
  `TaskReopenUseCase` exists at all is that the user reopened a *task*, and
  the project came with it. That is a rule about a task, and a task feature is
  where a rule about a task belongs. The projects feature owns the other
  direction — closing a project closes its tasks — and it owns that rule
  because the user closed a *project*.
- **The alternative was rejected:** moving the rule into `core/` would have
  made a shared layer own a rule about two features, and `core/` does not
  depend on features by construction. The cost of the current placement is one
  cross-feature import; the cost of the alternative is a dependency that
  points the wrong way.

## Consequences

- The projects list screen has a second list, and the state carries it on every
  variant — the closed projects do not depend on the search filter, so a state
  that dropped them would hide the way back behind a search.
- `FakeProjectsRepository.observeProjectsWithCounts` now filters the closed
  projects out, which production's `watchAllWithCounts` already did. The fake
  was showing a project the product hides.
- The reopen action is a repository call, not a use case: a use case that only
  forwarded to the repository would be a pass-through, and the detekt rule
  says so.

## Links

- `docs/decisions/2026-10-21-blocked-completion-guard.md`
- `docs/decisions/2026-10-22-the-blocking-rule-has-one-owner.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectsScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/TaskReopenUseCase.kt`
