# Tasks — a reminder that points at no view

**Status:** implemented · **Priority:** low — the infrastructure is done; only the
producer was missing.

## The decision first

- [x] Decide where a reminder inherits its saved agenda view from — the view the user
      was on, not the task's own section. Both screens that list a saved view's tasks
      count: the results screen and the view's editor
- [x] Decide what happens to a reminder whose saved view is deleted — the screen says
      "View not found" and goes back; nothing dead is left on the stack
- [x] Record the decision as an ADR — `2026-10-22-reminder-points-back-at-the-view-it-was-set-from`

## The producer

- [x] The reminder created from a saved agenda screen records that screen's identity —
      the agenda is the only place that knows the view, so it puts it on the route it
      opens the task with (`TasksRoute.Detail.sourceViewId`)
- [x] The stored reminder keeps it — `TaskRemindersSlot` reads the route's view and
      writes it into `Reminder.viewId`
- [x] The posted notification carries the target — the notifier already turned the
      stored view into the notification's deep link; the stored view was what was
      missing
- [x] The tap opens the view — `singularity://view/{id}` opens the saved view's task
      list, not its editor

## The tests

- [x] A reminder created from a saved view carries that view's identity —
      `TaskRemindersSlotTest`
- [x] A reminder created outside any saved view carries none — `TaskRemindersSlotTest`
- [x] A notification for such a reminder opens the view when tapped —
      `DeepLinkRegistryTest` matches the saved-view link to the results screen
- [x] A notification for a reminder whose view was deleted opens the app without a
      dead target — the results screen renders "View not found" and navigates back

## Explicitly not doing

- Changing the deep-link registry, the matcher, or the URI grammar
- Changing the notification payload format
- Giving pomodoro notifications a target — a phase ending has no view to point at
