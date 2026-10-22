# A reminder that points at no view

Status: **deferred**
Tags: **notification deep-link**

Related: ADR `2026-10-21-deep-link-registry`

## What

A reminder notification can carry a link back to the saved agenda view it came from.
The link side of that contract is complete: the notification payload is a URI, the URI
is matched by the deep-link registry, and the registry opens the right screen in the
right tab.

The data side is empty. A reminder records which saved agenda view it belongs to, the
notification carries that identity, and the tap handler opens the view — but **nothing
ever writes that identity**. The one place that creates a reminder leaves the field at
its default, so every reminder in the database has no view, so every notification is
posted without a target, so the tap opens the app at whatever screen it was on.

## Why it matters

This is the "feature that is finished but does nothing" defect: the infrastructure is
built, tested, and unreachable. Tapping a reminder notification lands on the app's
start screen instead of the list the reminder was about, and nothing in the code says
why — because the code path that would say so is never entered.

It was worth recording separately rather than fixing inside the deep-link work. The
infrastructure's shape is already decided and reviewed; what is missing is a producer
decision — *which* saved view a reminder belongs to — and that is a product question
about where the user is when they set the reminder, not a navigation-layer question.

## The gap, precisely

| Step | State |
|---|---|
| A reminder is created | the view it was created from is **not recorded** |
| A reminder is stored | the column exists and is always empty |
| The reminder fires | the stored view identity is read and passed to the notifier |
| The notification is posted | it carries a target URI |
| The user taps it | the registry matches the URI and opens the view |

The middle of that table is the only broken link. Everything above it and everything
below it exists and works.

## What is not in this change

- **No change to the deep-link registry, the matcher, or the URI grammar.** Those are
  settled by ADR `2026-10-21-deep-link-registry` and are not touched here.
- **No change to the notification payload format.** It already carries the target.
- **No change to pomodoro notifications.** A phase ending is not a reminder for a task
  in a saved view; it has no view to point at and correctly carries none.

## What has to be decided before implementing

- **Where does a reminder inherit its view from?** The saved agenda screen the user was
  on when they set the reminder is the natural source; the task's own section is the
  alternative. These differ when a task appears in more than one saved view.
- **Does a reminder keep the view it was created from, or the view it is being viewed
  in?** A reminder set on a task from one saved view and then edited from another is
  ambiguous.
- **What happens when the saved view is deleted?** The reminder survives; its target
  does not. Tapping the notification must not open a screen for a view that no longer
  exists.
