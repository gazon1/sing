# Tasks — a reminder that points at no view

**Status:** deferred · **Priority:** low — the infrastructure is done; only the
producer is missing.

## The decision first

- [ ] Decide where a reminder inherits its saved agenda view from — the view the user
      was on, or the task's own section
- [ ] Decide what happens to a reminder whose saved view is deleted
- [ ] Record the decision as an ADR, or as a section of the deep-link ADR

## The producer

- [ ] The reminder created from a saved agenda screen records that screen's identity
- [ ] The stored reminder keeps it
- [ ] The posted notification carries the target
- [ ] The tap opens the view

## The tests

- [ ] A reminder created from a saved view carries that view's identity
- [ ] A reminder created outside any saved view carries none
- [ ] A notification for such a reminder opens the view when tapped
- [ ] A notification for a reminder whose view was deleted opens the app without a
      dead target

## Explicitly not doing

- Changing the deep-link registry, the matcher, or the URI grammar
- Changing the notification payload format
- Giving pomodoro notifications a target — a phase ending has no view to point at
