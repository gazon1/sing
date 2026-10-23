---
change: the-proposal-has-a-lifecycle
verdict: pass
date: 2026-10-23
pr: 987
reviewed: b722d13366f44b3acd0887f49ba15d3d2bc3be8a
documents: sha256:283cde54dc0eb85229b939ab454a72352ea9a966935f5c3b8af70a1cb37199f6
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

## The second round

The phase-B work was reviewed in three rounds and passed. A second round of work followed
— the list the user brought, read off the code — and it was reviewed on its own, by a
review agent that had not seen the first round's verdict.

- **Round four — fail.** Two majors, no blockers.
- **Round five — pass.** Both majors closed, the minors that were named closed, and the
  change re-read against the contract it now carries.

### What round four found

**The compensation could run twice.** The take-back of the rows that were written lived in
the branch that handles a failure, and it ran in the scope that was already cancelled. A
cancellation landing in the middle of it left the rows half taken back; the `finally` then
took the same rows back again, and the second attempt on a row that is not there any more
fails — which was reported as a row that stayed. The one signal this design relies on to
find a real duplicate would have been corrupted by the mechanism meant to prevent it.

**The change shipped a requirement its own code did not meet.** REQ-PROP-016 says every
surface shows a failure that arrives as an exception; the task detail had a flag that said
"not here", so the requirement was false on one of the three surfaces. The flag was the
shape of the defect: the first thing written with one was a surface that set it to "no".

### What was done about it

The take-back runs where a cancellation cannot stop it, on both paths, and the rows that
are gone are remembered — so no row is taken back twice, and a row whose take-back failed
is tried again and named if it stayed. The flag is gone: every surface shows a thrown
failure, and the task detail reports it to the crash reporter as well as showing it,
which is what the review screen has always done. A test covers the double take-back: it
makes a write fail, cuts the take-back short, and asserts that no row is taken back twice
and that no row is left behind.

### What round five checked

The compensation end to end, against the three repositories the take-back goes through —
specifically what a *failed* take-back means for the row, since that is what makes the
retry meaningful and the report accurate. The reporting paths for a row that stayed, on
both the cancellation and the failure path. The removal of the flag against every
construction site of the style and every reference to it. The issue key the task detail
now uses against the one the MVI surfaces use. REQ-PROP-016 clause by clause, against the
code. The new test, red by hand against the pre-fix code.

### What was not verified

- **The `NonCancellable` protection itself is not exercised by a test.** The new test's
  fake throws out of the take-back rather than cancelling the scope, so what is covered
  is the root cause — no row is taken back twice — and not the property — the take-back
  survives a cancelled scope. The property is structural; naming it as unverified is
  more honest than calling it proven.
- **Room's cancellation semantics for a suspend DAO call.** A cancellation that lands
  between the database commit and the return of the write, or of the claim, leaves a row
  written and unrecorded, or a claim that was taken and not released. Whether the
  transaction rolls back on cancellation could not be checked from the sources available
  locally. It is the same shape as the defect this round closed, one step further down,
  and it is not fixed here.
- **An unknown kind is still a dropped row.** A *value* that does not read keeps the
  change, with the reason; a *kind* the codec was not taught to read is still dropped,
  because the codec has no way to name what it does not know. The round-trip test holds
  that line, and it is not a structural protection.
