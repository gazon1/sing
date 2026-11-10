---
title: "The saved-view results route keeps no floating action button"
date: 2026-12-01
status: accepted
---

# The saved-view results route keeps no floating action button

## Idea

Four briefs were batched into one change to give task-listing agenda routes an
"Add task" action. Three of them agree; one does not, and the disagreement is
about a single route.

- **#1952** — "The saved-view list, the saved-view viewer, the saved-view editor
  and the saved-view creator keep drawing no button", with an acceptance
  criterion that a test says so.
- **#1958** — "The screens that are not task lists — the saved-views list, the
  saved-view editor, and the saved-view *results* — keep no shell button." Its
  out-of-scope line repeats it: "The saved-views screens and their own create
  button."
- **#2057** — lists "the route showing a saved view's results" among the
  task-listing routes that must carry the button.

So `AgendaStartRoute.SavedAgendaResults` is required to resolve to `null` by two
briefs and to an action by one.

## Decision

The route resolves to **no** action. #1952 is the brief the change was opened
against, #1958 says the same thing twice, and the two outnumber #2057.

## Rationale

The results screen is a saved view *applied to* the agenda, not a task list the
shell owns. The view is the thing that decides what is in it, and the view owns
the way a task is added to it. A shell FAB there would create a task that
satisfies no part of the view — it would appear in the list only if it happened
to match the view's own query, which is not what the button promises.

The other direction is not unreasonable and may win later. It has a cost the
button appears to hide: the created task arrives with no due date, exactly as on
Inbox (#2057 asks for that), and then may or may not show up in the list the
person pressed the button on.

## Consequences

- `FabActionResolver`'s saved-view branch returns `null` for `SavedAgendaList`,
  `SavedAgendaResults`, `SavedAgendaEdit` and `SavedAgendaCreate`, and carries the
  comment that explains the contradiction in place.
- `FabActionResolverTest.expectedAddTask` classifies `SavedAgendaResults` as
  `false`. The resolver and that classification are the same decision written
  twice: moving the route into the "Add task" branch without flipping the test
  fails the suite, and that is deliberate.
- **This is the item in the change that needs a human to ratify.** If #2057 is
  meant to win, move `SavedAgendaResults` into the "Add task" branch in
  `FabActionResolver.kt` *and* flip it in `FabActionResolverTest.expectedAddTask`,
  in one commit.
- The branch was flipped that way once during the change, on the reading that the
  newest classification wins, and flipped back: the brief this change answers is
  #1952, and #1952 says in so many words that the saved-view agenda routes keep
  no button. A route the brief covers explicitly does not get reclassified by a
  sibling brief the brief does not mention.
- #2057's first acceptance criterion is therefore literally unmet by this change.
  That is a recorded divergence, not an oversight.

## Why the change is much larger than "add three branches"

The brief's shape is three branches in one `when`. The change is ~440 inserted
lines across 13 files because of one thing: #1952 and #1958 both require that the
created task *arrive seeded*, and no route could carry a seed.

"Add task" for a project agenda that opens an unseeded task is the
"looks-done-but-does-nothing" shape — the button is present, the acceptance
criterion about the button is met, and the task silently lands in the Inbox. So
the project and tag ids had to be carried end to end:

    FabActionResolver
      -> AppDestination.TasksStartRoute.Create(projectId, tagIds)
      -> TasksRoute.Create(projectId, tagIds)        (NavStartRoutes.toTasksRoute)
      -> TaskCreateScreen(...)                        (TasksNavGraph)
      -> TasksDiModule viewModel factory
      -> TaskCreateViewModel(initialProjectId, initialTagIds)
      -> taskDraftSeed -> TaskDraft.projectId / .tagIds

Each link is pinned by a different test, and no single test spans the chain:
`FabActionResolverTest` stops at `AppDestination`, `NavStackSeedTest` covers
`AppDestination` → `TasksRoute`, `TaskCreateViewModelTest` covers the
constructor → draft. `NavStackSeedTest` and the `TasksNavGraph` create entry are
the two seams where an omission would leave the feature inert with every other
test still green.

`StableContentKey` gained the scope as well, for the reason it already carries the
date: a create screen opened from a project is not the screen opened from a tag,
and one screen's draft is not the other's. Without that, two different seeded
editors would share one content key and one saved draft.

Precedence between the seed and a restored draft is **the restored draft wins**,
because it carries what the person was already writing. That is
`DraftMviViewModel`'s existing seed-if-empty rule
(`restored != null && draftState.value == initialDraft && !draftState.isDirty`);
the seed is `initialDraft`, so it is used only when there is nothing to restore.
`TaskCreateViewModelTest` should keep a case for it, since a re-opened editor is
the one path where "which one wins" is not visible from the call site.
