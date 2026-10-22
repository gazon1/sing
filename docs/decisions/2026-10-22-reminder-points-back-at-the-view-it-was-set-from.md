---
title: A reminder points back at the saved view it was set from
status: accepted
date: 2026-10-22
tags: [reminders, agenda, notifications, navigation]
---

# Context

A reminder notification can carry a link back to the saved agenda view it came from.
The link side of that contract was finished: the notification payload is a URI, the URI
is matched by the deep-link registry, the registry opens the right screen in the right
tab, and the notification payload format carries the target.

The data side was empty. `TaskReminderEntity` has a `view_id` column, `Reminder` has a
`viewId`, the notifier's `post(...)` takes a `viewId`, and the alarm handler reads it and
builds a `singularity://view/{viewId}` URI. Nothing ever wrote it. The one place that
creates a reminder — `TaskRemindersSlot.setReminder` — left the field at its default, so
every reminder in the database had no view, every notification was posted without a
target, and every tap opened the app wherever it happened to be.

The OpenSpec change `a-reminder-that-points-at-no-view` recorded the gap and left three
questions open, because they are product questions rather than navigation questions:

1. Where does a reminder inherit its view from — the saved agenda screen the person was
   on, or the task's own section?
2. Does a reminder keep the view it was created from, or the view it is being viewed in?
3. What happens when the saved view is deleted?

# Idea

The agenda is the only place that knows which saved view the person was looking at. By
the time the task's detail screen is up — which is where a reminder is set — that list is
gone. So the view has to travel with the route, from the agenda's navigator to the task
detail screen, and only the detail screen can write it onto the reminder.

# Decision

**1. The view the person was on when the task was opened.** A task opened from a saved
view's list (or from that view's editor) carries that view's identity. A task opened
from a tab, from search, from the calendar, or from a project carries none: there is no
view to point at, and a reminder that points at nothing is not better than one that
points at the wrong thing.

The task's own section is *not* the source. A section belongs to a view's definition, so
a section without a view is a section of nothing; and a task can be in more than one
view, in which case the section answers a different question than the one the person
asked.

**2. The reminder keeps the view it was created from.** A reminder is a record of an
intent — "remind me about this task in this list" — not a live query. Editing the task
later from a different view does not rewrite where the reminder came from; the person
who set it is waiting for that list.

**3. A deleted view leaves the reminder alone, and the tap lands safely.** The reminder
survives; its target does not. The tap opens the agenda tab and the saved view's
screen, which reports that the view is not found and goes back — the same path the app
already takes when a saved view is deleted while it is open
(`SavedAgendaViewState.NotFound`).

**The link opens the view's task list, not its editor.** `singularity://view/{viewId}`
resolves to `SavedAgendaResults`. The link arrives in a reminder, and a reminder is about
the tasks in the list, not about the list's definition. The link was never reachable
before this change — no producer wrote the view id — so defining its target now costs
nothing.

# Rationale

The alternative to carrying the view on the route is to look it up when the reminder is
set, from wherever the person happens to be. That is the wrong answer: the person is on
the task's detail screen at that moment, and the screen does not know which list the
task came from. The information exists in exactly one place — the agenda — and it is
gone by the time it would be needed unless it is carried.

Carrying it on the route is also the same shape as every other piece of context the app
already carries on a route: the task id, the due date, the section prefill. The route
is the app's answer to "how did the person get here".

# Consequences

- `AppDestination.TasksStartRoute.Detail` and `TasksRoute.Detail` carry a
  `sourceViewId`. Both are `@Serializable` with a default, so a route saved by an older
  build still deserialises — the field is simply absent.
- `AgendaNavigator.openTask` reads the view from the agenda stack's top. It is the only
  caller that sets it; every other way into a task leaves it null.
- The task detail's ViewModel takes the route rather than the task id, so the view id
  reaches the reminders slot. A nullable Koin parameter would have been the smaller
  change and the more fragile one.
- A reminder written from a saved view now carries that view, and its notification
  carries a target. The notification's tap opens the view's task list.
- The saved-view link's target changed from the view's editor to the view's task list.
  Nothing pointed at it before, so no notification changes its behaviour.

# Links

- OpenSpec change `a-reminder-that-points-at-no-view` — the gap this closes.
- ADR `2026-10-21-deep-link-registry` — the registry that matches the link.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaNavigator.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskRemindersSlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/TasksRoute.kt`
