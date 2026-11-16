---
title: "Attention items are derived and shown in the app first; OS delivery waits for its contract"
date: 2026-11-16
status: accepted
tags: [tasks, statistics, reminders, health, notifications]
---

# Attention items are derived and shown in the app first; OS delivery waits for its contract

## Context

Issue #2980 asks for two things that are easy to confuse: *derived in-app nudges* — a
short list of what wants the user's attention — and *an evaluation of OS-level
delivery*. The issue separates them on purpose, and the reason is visible in what the
app already has.

Everything a nudge needs is already computed. The Health tab counts overdue, blocked
and stagnant through `HealthRules`; the agenda's date buckets answer "due today" and
"due tomorrow"; `Blocking.blockedTaskIds` answers "waiting on something that is not
finished"; `Project.dueDate` is stored and shown. What the app does not have is the
shortlist: which of those items should be dealt with first, and why that one.

The temptation is to answer that with a notification — push the item out of the app
until the user acts. But a notification is a *delivery mechanism*, and it cannot be
designed before the thing it delivers is designed. A system notification needs at
least four answers that a nudge list does not yet have:

- **When** may a nudge raise one?
- **How** is a repeat suppressed?
- **How long** does a raised notification stay valid?
- **What** invalidates a raised notification that was never tapped?

Answering those against a list that does not exist yet is how notification systems end
up firing for the same task every morning, or firing for a task the user completed an
hour ago.

## Idea

Three shapes were considered:

1. **In-app list and OS notifications together.** The whole feature in one change.
2. **OS notifications first**, as a way to be useful without touching the UI.
3. **In-app list first**, with the OS-delivery contract written down before any
   notification code exists.

## Decision

Option 3.

The nudge list is a **projection**, not a model. It is computed from the task and
project rows every time it is shown, it is never stored, and it is not a second
definition of overdue, blocked or due-today: those rules are asked, not rewritten.
The four signals — overdue, due soon (today or tomorrow), blocked, project deadline —
are ordered by relevance, and an item that qualifies under several appears once,
under the most relevant one.

The list lives on the **Statistics Health tab**, above the bucket cards. That tab
already means "what needs attention", already loads the whole task list, and already
drills down into buckets; adding a section there is a smaller change than a new
screen, and it puts the shortlist next to the counts it refines. Agenda Today was
rejected: it already renders overdue and today sections with per-row badges, so a
nudge list there would show the same tasks twice on one screen.

A nudge can be **set aside for now**. The dismissal is transient — it lives in the
screen's own state, it is gone when the screen is left, and it changes nothing about
the task or the project. A dismissal that were stored would need its own table, its
own sync story and its own expiry rule; the in-app version needs none of that, and it
answers the only question it is asked ("not now").

The screen's own state, and not the ViewModel's, is the point. A ViewModel outlives
the screen it belongs to: the navigation keeps the Statistics entry alive while
another destination is on top of it, so a dismissal held in the ViewModel would still
be there when the user came back — the item they set aside would stay set aside, and
the list would go on claiming that nothing needs attention. Plain screen state (not
saveable state) ends with the visit it belongs to.

Two more decisions the list makes explicit, because both are places where the
obvious reading of "the same rules as everywhere else" would be wrong:

- **An item with no date goes last.** A task that waits on something and has no due
  date has no claim on being early, and sorting it ahead of the items that have a
  date would say "this first" about an item that cannot be.
- **A deferred task is not named, but it is still unfinished.** It is parked, not
  done, so it counts towards a project's deadline — a deadline is a promise about a
  body of work rather than about one row.

**The OS-delivery contract is written here, and no notification code is written
yet.** When the second phase comes, it starts from these rules:

- **Trigger.** A nudge may raise a system notification only when the app is not in the
  foreground, and only for a nudge in the first three signals (overdue, due soon,
  blocked). A project deadline is a planning fact, not an interruption.
- **Dedup.** One notification per *nudge*, identified by the nudge's stable key
  (`task:<id>` / `project:<id>`) and its signal. The same key never raises twice for
  the same reason; a nudge whose signal changes (overdue → due soon cannot happen, but
  blocked → overdue can) is a new nudge and may raise again.
- **Expiry.** A raised notification is valid for the calendar day it was raised on.
  The next day's list is a new list, and the old notification is stale by definition.
- **Invalidation.** A notification is withdrawn the moment the item it names stops
  qualifying — the task is completed, discarded or deferred, or the project is closed
  — and when the item it names is no longer in the derived list. A notification that
  points at a task that no longer needs attention is worse than no notification.
- **Platform.** Android arms a one-shot alarm per nudge (the app already owns
  reminder alarms). Desktop has no alarm of its own, so a desktop nudge is delivered
  by a transient timer that dies at logout and is re-armed at launch — which is why
  the desktop build must never claim a nudge was "delivered" while the machine was
  off.

## Rationale

The in-app list is the thing worth having; the notification is a way of surfacing it
later. Building the list first means the delivery question is asked about a list that
already has an order, a reason and a dismissal rule — so "which of these is worth an
interruption?" has an answer, and the answer is not "all of them".

The dismissal is deliberately not stored. It is the one piece of state in the feature,
and making it transient is what keeps the feature free of a table, a sync rule and an
expiry policy. If the in-app dismissal turns out to be wanted across sessions, that is
a change of scope with a schema of its own, not something to decide in advance.

The platform difference in the contract is written down rather than smoothed over: a
desktop timer that dies at logout is a real limit, and a design that pretends both
platforms behave the same will deliver nothing on one of them.

## Consequences

- The Health tab answers two questions on one screen: how many problems there are
  (the cards) and which to deal with first (the list).
- Nothing is stored, so nothing can go stale, and there is no migration, no sync
  conflict and no backup question.
- The rules for overdue, due soon and blocked are asked of the selectors and
  `Blocking` rather than written again, so the list asks the same rules the
  counts ask. It names a subset of what they count: the counts cover deferred
  work and work in a closed project, and the list names neither. A deferred task
  with a past due date is therefore counted as overdue by the card beside the
  list and not named by the list — a difference between the two surfaces, not a
  disagreement about the rule they both ask.
- The nudge list is bounded on screen and says how many items it did not show — the
  list is a shortlist, and a shortlist that hides its own size is a lie. The count
  is of the items the list did not show, whether they were left out by the bound or
  set aside by the user.
- The screen reads "today" through the injected time-zone provider, the way the
  agenda does. Two surfaces that disagreed about what today is would show two
  different lists of the same tasks.
- **No notification exists.** The contract above is a decision, not an
  implementation: when the second phase starts, it starts by turning each bullet into
  a rule a test can pin, and by deciding what happens on a device that was off.

## Links

- Issue #2980 — "feat(reminders): add derived in-app nudges and evaluate OS delivery
  separately"
- OpenSpec change `derived-attention-nudges`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskHealth.kt` —
  the health classifier the list asks rather than rewrites
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/AttentionNudges.kt` —
  the projection
- `shared/src/commonMain/kotlin/com/singularity/todo/core/reminders/` — the reminder
  machinery the Android side of the second phase would reuse
