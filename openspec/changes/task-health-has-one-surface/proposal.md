# task-health-has-one-surface

## What

Make the three surfaces that already report task health — the Statistics Health tab,
the AI read tools, and the Agenda's built-in health views — answer the same question
the same way, and make each of them say *why* a task is in a bucket.

Concretely: one classifier owns the overdue → blocked → stagnant precedence; the
stored staleness threshold is interpreted once; the Stagnant drill-down counts with
the user's threshold rather than the default; and the health cards state the rule
their number was made with.

## Why

Issue #2977 asks to close the discoverability gap between the health information the
app already computes and an actionable "what needs attention?" experience, without
building a second health model. Its audit found the model was already there and the
surfaces built on it had drifted.

`TaskHealth` says health is derived, never stored, and that a bucket is not a score.
That is sound, and it is why the answer is computed in more than one place — the
statistics screen and the AI tools each call the domain rule. But the *precedence*
was written twice, and the staleness setting meant two different things to the two
copies. On a fresh install they agreed; on a user who had configured the threshold
they did not. The drift was invisible because no test compared the surfaces, and the
two cases it produced — a card saying "12" and the list it opened holding a different
set — read as a data bug rather than as two rules.

So this change does not add a surface. It makes the existing ones honest.

## Scope

### In scope

- One owner for the health precedence and for the meaning of the stored staleness
  threshold, in the tasks domain where the bucket vocabulary already lives.
- The AI read tools and the Statistics summary both classify through it.
- The Stagnant drill-down reads the stored threshold, so the card's count and the
  list it opens describe the same set.
- The health cards state the rule each count was made with.
- A test that drives both surfaces over the same tasks and asserts they agree,
  including the stored-`0` case and the window and due-date boundaries.

### Out of scope

- A new combined "Attention" list, route, tab or saved surface. The audit found no
  workflow the existing Health tab and its three drill-downs cannot express, so the
  smallest useful surface is the one that already exists.
- A second health enum, a stored attention table, or a second priority scale. The
  issue forbids all three and nothing here needs them.
- "Due soon" as a new health bucket. No such window exists in the model today, and
  inventing one would be the duplicate date calculation the issue warns against.
- Project deadline risk. A project carries a due date, but no health surface
  consumes it and adding one would be a new signal, not a closed gap.
- The Upcoming / Today / Inbox vocabulary decisions owned by #2828, #2675 and #2839.

## References

- Issue #2977 — "feat(agenda): close the Attention discoverability gap using existing
  Health and Agenda"
- ADR `2026-11-15-task-health-has-one-classifier`

## Status

**Implemented.** The classifier, the threshold, the drill-down and the card copy are
in the tree; the agreement test drives both surfaces.
