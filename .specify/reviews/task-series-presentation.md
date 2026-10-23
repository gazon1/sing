---
change: task-series-presentation
verdict: fail
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/976
reviewed: d7bdc84a79d36cb0a9d47161c3ab8379c2615c2b
documents: sha256:7b49474de4b25afbcb9eb6fc3c234d2b4e829f798145725a3d62847c8e5c0bc8
---

## What was reviewed

The whole change: `openspec/changes/task-series-presentation/` — the proposal,
the design, the tasks and the `tasks` spec — against the code it describes:
the mark of a series in the calendar and in the day's list, the undo of a
deleted series, and the statistics that count a series as one work.

The reviewer was a separate agent given the diff (`git diff origin/main...HEAD`),
the ADRs and the OpenSpec change, and nothing else.

`reviewed:` is the head of the pull request as it landed — the state of the
branch the review judged, and the branch the fixes are in. This pull request
landed by merge, so the commit is in the history of `main` and a reader with
the history can look at it.

`documents:` is the digest of the contract as the verdict speaks for it — the
change's proposal and its spec delta. The contract has not moved since the
review, so the digest is the one the gate recomputes.

## What the review found

The verdict was **fail**. One blocker and one should-fix item, both about the
undo:

1. **Blocker — the undo of a deleted series was never visible.** The delete
   emitted the undo event and then the navigation back. `NotificationHost` keeps
   a single notification slot, so the navigation replaced the undo before it
   could be drawn, and the screen that was popped disposed the `Popup` the
   toast lived in. Everything around it was correct — the rows were archived,
   the message named the number of dates, the button restored the archived ids
   — and the user still never saw the way back. The spec
   (`SHALL offer the way back`) was not met.

2. **Should-fix — the calendar's chip passed a magic `1`** as the number of
   dates, because the marker's API had no way to say "this is a series, and
   this surface does not show the count".

3. **Should-fix — the ADR about the new order claimed the screen shows the
   "not found" state behind the toast.** The observation that feeds the detail
   screen does not filter the archived rows out, so the screen keeps showing
   the archived task. The ADR now says what the user sees.

## What was done about it

The verdict stays **fail**, because the review found something: that is what
this file is for. All the findings were fixed in the same branch:

- The offer comes before the navigation. `Notification.Undo` grew an
  `onDeclined` callback; the host calls it when the toast ends without the
  action being taken; the task detail navigates back there and nowhere else.
  ADR `2026-10-23-the-offer-comes-before-the-navigation.md` says why, and the
  spec was corrected to state the order the code now keeps.
- The marker's API says what it means: `SeriesMarker(isSeries, dates)` — the
  chip passes `isSeries = true` and no count, the day's list passes the count.

The rule has a check that can fail: `TaskLifecycleSlotTest` asserts that
deleting a series does not navigate away. The defect this review found was
invisible to every other check — the archive was right, the ids were right, and
the toast was right; it was the order that was wrong.

## Links

- ADR `docs/decisions/2026-10-23-the-offer-comes-before-the-navigation.md`
- ADR `docs/decisions/2026-10-22-the-series-says-one-thing-about-the-work.md`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskLifecycleSlotTest.kt`
