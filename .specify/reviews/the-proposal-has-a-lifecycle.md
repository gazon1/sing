---
change: the-proposal-has-a-lifecycle
verdict: pass
date: 2026-10-23
pr: pending
---

## What was reviewed

The whole of phase B of `gazon1/feat/the-write-path-is-closed`, in three rounds by
three separate review agents, each one reading the diff and the code around it — not
the summary of the previous round.

- **Round one — fail.** Three major findings and three minors.
- **Round two — fail.** Two major findings and four minors. The first round's findings
  were checked against the code and found answered; the second round found the answer
  itself had a hole.
- **Round three — pass.** No blocker and no major. The six minors it named were closed
  before the merge, and the tests that cover them were added.

## What the rounds found

**A decision that outlived the change.** The claim of a change is the decision and the
dispatch is the work, and the work happens *after* the claim. A dispatch that failed
left the item `Confirmed`: the user saw a change as done that was never applied. The
claim is released now — a compare-and-set back to `Pending`, with the same ownership
and status guards the claim had — and the reason sits next to the change.

**A change that wrote half of itself.** A change that adds three checklist items and
fails on the second left the first behind, and the change goes back to waiting, so the
user tries again and the row that stayed is there twice. The dispatch now writes all of
its rows or none of them.

**A documented behaviour that was an error.** The tool that sets a due date has always
said that an empty value clears it. The typed vocabulary refused the empty value, so
the documented behaviour was a tool error — and a regression against the phase before.
The date and the time hold the absence of a value now, and the stored shape is
unchanged, so the rows written before the typed dictionary keep reading.

**Failures nobody could see.** The note editor emitted its failures and nothing read
them, so every failure on that surface was invisible — including a refused change. The
note and the task detail carry the reason per change now, next to the change it belongs
to, and the proposals screen reads the error events it was never reading.

## What was checked besides

- The typed vocabulary end to end: reading, writing, encoding, decoding, planning,
  displaying, and the round trip through the codec.
- The codec: a kind it was not taught to read is dropped silently, and the round-trip
  test is what holds it.
- The write tools: after `create_tag` became a proposing tool, no tool writes, and the
  invariant is a test that reads the behaviour and the source — not a list of
  exceptions kept in a file.
- The three surfaces that show a proposal: the proposals screen, the note editor and
  the task detail — one card, one behaviour.
- The event buses: the note editor merges two of them into one notification flow, and
  the merge adds no consumer to a channel that has one.

## What was not reviewed

- The performance of the new reads (the plan is rebuilt when the row changed between
  the read and the claim).
- The sync path of the rows the dispatch takes back: a task is archived rather than
  deleted, and the archive is where a rolled-back subtask goes.
- The multi-profile behaviour of the release: the guard is the user's, and a profile
  switch between the claim and the release was not exercised.
