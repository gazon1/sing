---
title: "The refresh is one run, and one run has one owner"
date: 2026-10-25
tags: [ui, sync, tasks, architecture]
status: accepted
---

## Context

The application synchronises on a schedule and on a handful of triggers — the
periodic driver, the app coming to the foreground, the network coming back.
None of them is a user asking for it. A user who opens the application and
looks at a list cannot make that list current; they wait for the timer, or
they leave and come back.

Archiving completed work is the second half of the same wish. It exists today
as one button on one screen — the archive screen — while the completed tasks
that clutter every working list stay in every working list. The user has to
know that the archive screen exists, walk to it, and press the button, before
their "Today" stops showing work they finished yesterday.

Both are the same gesture: *bring this screen in line with what has already
happened*. The application had no way to express it.

The synchronisation core already anticipated the gesture. The coordinator that
funnels every synchronisation trigger says so in its own documentation: the
periodic driver, the pull-to-refresh gesture, the scheduled job and the alarm
broadcast all arrive at the same place and are collapsed into one cycle. The
gesture was named before it existed.

Three things made the obvious implementation wrong.

**There are eighteen screens.** The agenda serves three bottom-bar routes as
one screen, and the rest are the tabs, the menu destinations and the detail
screens. An implementation that lives in each screen would run the archive step
eighteen times and start eighteen independent cycles against one outgoing
queue.

**Archiving and synchronising are one operation, not two.** Moving completed
work into the archive writes those rows to the outgoing queue. A cycle that
pushed before the archive step ran would leave the server holding the
un-archived copy, and the next download would restore every task the user had
just cleared away. The order of the two steps is the whole difference between
a refresh that tidies up and one that resurrects.

**The report has an owner.** A refresh that ends in a message has to put that
message somewhere, and "somewhere" cannot be "everywhere": the screens that
happen to be mounted would each report the same refresh. The screen that asked
is the one that answers.

## Idea

1. One owner of the refresh run, reached through one shared container that the
   screens wrap their content in. The screens keep their own view models and
   learn nothing about synchronisation.
2. Extend the synchronisation coordinator so that it archives as well as
   synchronises, and expose the gesture from it.
3. Give every screen its own refresh implementation, and let each one call the
   synchronisation facade directly.

## Decision

We did (1).

A new owner of the refresh run sits in the core. It takes the archive step and
the synchronisation step, runs them in that order, and returns what happened.
Its run lives in its own scope rather than in the caller's, so a refresh that
the user walks away from still finishes. A request that arrives while a run is
going joins that run instead of starting another.

The core declares the narrow contract the archive step needs rather than
taking the archive feature's own port. The archive feature's port inherits it,
so the implementation does not change by a line — but the core no longer names
a type that belongs to a feature.

One shared container carries the gesture, the indicator and the report. It
raises a shared view model of its own, which lives by the rules of the
navigation's view-model store: the report survives the user leaving the screen
and appears when they come back.

The result belongs to the screen that asked for it. The report is a one-shot
event on that screen's view model, and no other screen reports it.

## Rationale

(1) is the only option that makes the archive step and the synchronisation
step one operation. The owner is the place where the order of the two steps is
decided, and that is the whole reason the two belong together. It also puts
the "one run at a time" rule in one place instead of in eighteen, and it keeps
the screens' own view models free of a state that is not theirs.

(2) would have put archiving inside the synchronisation core, and the
synchronisation core holds no repositories — that is not a style preference but
a dependency-cycle rule: a cycle that the dependency injector resolves
recursively overflows the stack, and the compiler cannot see it. Making the
core know about archiving puts it one step from that cycle.

(3) is the shape the codebase already warns about. A screen that reaches for
the synchronisation facade directly gets the cycle, but not the archive step,
and not the order between them. Eighteen screens that each remember the order
will not all remember it.

The narrow contract in the core is the same reasoning one level down. The core
declares what it needs; the feature's port inherits it. The dependency points
from the feature to the core, as it does everywhere else, and the core's
dependency on the feature's type disappears.

The report's owner is decided by the same rule the event bus already follows:
an event has one consumer. The screen that asked is that consumer, and it is
the only one.

## Consequences

- A new package in the core holds the whole refresh: the result type, the
  state, the event, the owner of the run, the shared view model and the shared
  container.
- The core's dependency on the archive feature is replaced by a narrow port of
  its own. The archive feature's port inherits it and nothing else changes.
- The archive step runs before the synchronisation step, so the archived state
  leaves the device within the same gesture. The requirement is explicit
  because the alternative — synchronising first — resurrects every task the
  user just archived.
- The two steps are independent failures. A refresh that cannot reach the
  server still archives, and says that it did.
- The screens' own view models are untouched. Eighteen screens change by
  wrapping their content, not by learning about synchronisation.
- The pomodoro gains a vertical scroll it did not have, because a gesture needs
  something to pull and it had none. The task detail already had one — in the
  editor scaffold — so its structure did not change. On the calendar the only
  scrollable is the hour grid, which is scrolled to the current hour, so the
  gesture is reachable only at the top of the grid.
- The explicit "archive all completed" action on the archive screen stays. The
  refresh is added beside it, not instead of it.
- The core's notification set gains a non-modal variant, `Notification.Toast`.
  The report confirms an action the user just asked for, and the two dialog
  variants (`Text`, `Error`) make the user dismiss it before doing anything
  else. The host renders the new variant in the same snackbar host as the undo
  offer, so a report cannot appear on top of an offer that is still running.
- Four screens in scope already had a notification host of their own (the
  archive, the task detail, the chat and the plans). They now carry two: one
  for their own events and one for the refresh's. A screen whose own report
  and the refresh's report are on screen at the same moment shows two
  snackbars in the same place. The overlap is brief and cosmetic, and merging
  the two hosts would mean the refresh's events flowing through a screen's own
  event stream — the one thing the event bus' single-consumer rule forbids.
- The container resolves its view model from Koin, and renders the content
  without the gesture when there is no Koin at all. That is the IDE preview:
  a preview has no dependency graph, and the repository's rule is that a
  preview must not need one. "No Koin" means no **global** Koin — both
  shipped applications start theirs with `startKoin`. The scope a
  composition carries of its own is not consulted, because the only public
  ways to ask for it are APIs this project does not use: `LocalKoinScope` is
  deprecated and `LocalKoinScopeContext` is `@KoinInternalAPI`. The
  consequence is narrow and known: a test that mounts the application inside
  an isolated Koin scope renders the screens without the gesture. A Koin that
  exists but does not hold the binding still fails loudly — the fallback is
  for "no Koin", not for "wrong Koin".
- The gesture needs a scrollable to drag. The container does not supply one:
  a scroll container measures its content with an unbounded height, and a
  content that fills the screen would collapse to nothing. The screens that
  already scrolled keep their scroll; the one that did not (the pomodoro)
  grew one on its own root, in the order the rest of the codebase uses.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncCoordinator.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncRepository.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/domain/port/ArchiveRepository.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/data/TaskDaoArchiveRepositoryImpl.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/MviViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/EventBus.kt`
- ADR `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
- ADR `2026-10-22-an-event-bus-has-one-consumer`
- ADR `2026-09-08-task-archive-restore-contract`
- `specs/015-pull-refresh-sync-archive/spec.md`
