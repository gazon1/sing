# derived-attention-nudges

## What

Add a derived list of **nudges** — the handful of items that want the user's attention
right now — and show it on the Statistics **Health** tab, the surface the app already
uses to answer "what needs attention?".

The list is computed from facts the app already holds (a task's due date, its
dependencies, a project's deadline) and is never stored. There is no new table, no new
scheduler, no new repository, and no model of its own: every nudge is a projection of
the existing task and project rows through the existing selector rules.

Four signals, in a strict relevance order:

| Signal | Meaning |
|---|---|
| Overdue | past its due date and still open |
| Due soon | due today or due tomorrow |
| Blocked | waiting on an unfinished prerequisite |
| Project deadline | the project's deadline has arrived or passed and it still has unfinished work |

Each nudge names one item (a task or a project), states one reason, and opens that item.

## Why

Issue #2980 asks for in-app nudges that are *derived*, and for OS-level delivery to be
evaluated separately rather than bundled in.

The audit behind this change found that everything the nudge list needs is already
computed by the app — and computed twice, in two places that cannot see each other:

- the Health tab counts overdue, blocked and stagnant through `HealthRules`;
- the agenda's date buckets (`Today`, `Tomorrow`, `Overdue`) already answer "due today"
  and "due tomorrow";
- `Blocking.blockedTaskIds` already answers "waiting on something that is not finished";
- `Project.dueDate` is already stored and already displayed on the project screen.

What the app does *not* have is the one thing a user actually asks for: a short,
ordered list of the specific things to act on, each with a reason and a way in. The
counts answer "how many", the drill-downs answer "which ones", but neither answers
"which one should I deal with first, and why that one?".

So this change does not add a second health model. It projects the existing model into
an ordered list and puts it on the surface that already means "attention".

## Scope

### In scope

- One pure projection from the task list and the project list to an ordered list of
  nudges, in the tasks domain where the health vocabulary already lives.
- The four signals above, in the relevance order overdue → due soon → blocked →
  project deadline, one nudge per item, so an item that qualifies under several
  signals appears once, under the most relevant one.
- The nudge list on the Statistics Health tab, above the existing bucket cards: the
  most relevant items first, a bounded number of them, and the count of the rest.
- Each nudge opens the item it names, and states the one reason it is there.
- A transient "not now" dismissal of a single nudge, which changes nothing about the
  task or the project and does not survive leaving the screen. The dismissal is held
  by the screen itself: a ViewModel outlives the screen — the navigation keeps the
  Statistics entry alive while another destination is on top of it — so a dismissal
  held there would outlive the visit it belonged to.
- The contract for the later OS-level delivery — when a nudge may raise a system
  notification, how repeats are suppressed, how long a raised notification stays
  valid, and what invalidates it — written down in the ADR, **not implemented**.

### Out of scope

- Any operating-system notification. #2980 asks for it to be evaluated separately, and
  a notification needs a trigger, a deduplication rule, an expiry and an invalidation
  rule before it may exist. Those are written down; the code is not written.
- A new table, a stored "attention" state, a stored dismissal, or a second priority
  scale. Everything here is derived, and derived values are never stored.
- A new screen, route or tab. The Health tab is the surface that already means
  "attention", and it already loads the data this needs.
- Stagnation as a nudge signal. #2980's relevance order does not name it, and the
  Health tab already counts it as its own bucket.
- A nudge for a task whose project is closed, for a deferred task, or for finished or
  discarded work. A nudge is about work the user still intends to do. A deferred task
  is not named on its own, but it is still *unfinished*, so it counts towards a
  project's deadline — that one is about a body of work rather than about one row.
- Any AI, model call or natural-language generation. The reasons are stated by the
  rules that produce them.

## References

- Issue #2980 — "feat(reminders): add derived in-app nudges and evaluate OS delivery
  separately"
- ADR `2026-11-16-derived-attention-nudges-in-app-first`

## Status

**Implemented for the in-app phase.** The projection, the Health tab section and the
dismissal are in place and covered by tests. The OS-delivery contract lives in the ADR
and has no code, so the change stays active until that phase is decided.
