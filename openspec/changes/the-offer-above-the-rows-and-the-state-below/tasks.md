# Tasks — the-offer-above-the-rows-and-the-state-below

- [x] shared-ui/ Split the editor's single supplied-content place into two: an upper one drawn
      directly under the description, and a lower one drawn after the attribute rows. Both
      optional, both null by default, so a caller that fills neither draws what it drew.
- [x] shared-ui/ Name the lower place for what it carries — the task's state — and make the two
      places' documentation read as a pair: one asks, one shows. No comment still calls the
      lower place "everything the editor does not know about".
- [x] shared-ui/ The task detail fills the upper place with the first-run offer and the pending
      proposals, and the lower place with the child work — tags, recurrence, checklist,
      subtasks, attachments, backlinks, series, time tracking, logbook — in the order it had.
      Every proposal action keeps its intent: confirm one, confirm all, reject with a reason,
      edit, dismiss.
- [x] desktopApp/ Verify the child work stays below the attribute rows:
      `TaskDetailTimeTrackingSectionTest` asserts the due-date row is laid out above the
      checklist section. That is the half of the order that needs no proposal seeded.
- [x] desktopApp/ Keep the time-tracking guard honest after the move: the section now sits below
      the fold, so it scrolls to it before asserting it is displayed, rather than weakening the
      assertion to "is composed".
- [ ] desktopApp/ Pin the upper half of the order — a proposal drawn above the attribute rows —
      with a test that seeds a proposal. Left open on purpose: it needs a proposal-seeded
      detail screen, which is its own piece of work, and the brief for this change leaves the
      proposal placement test out of scope.
