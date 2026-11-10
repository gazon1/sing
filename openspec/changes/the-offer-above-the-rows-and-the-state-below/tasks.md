# Tasks — the-offer-above-the-rows-and-the-state-below

- [x] shared/ The editor draws the offer slot (`bannerSections`) directly under the
      description, above the attribute rows, and the state slot (`extraSections`) after
      the last attribute row. Both optional, both null by default, so a caller that fills
      neither draws what it drew.
- [x] shared/ The two slots' documentation says which is drawn where and what each carries:
      the upper asks, the lower shows.
- [x] shared/ The task detail fills the upper slot with the first-run offer and the pending
      proposals, and the lower slot with the child work — tags, recurrence, checklist,
      subtasks, attachments, backlinks, series, time tracking, logbook — in the order it
      had. Every proposal action keeps its intent: confirm one, confirm all, reject with a
      reason, edit, dismiss.
- [x] desktopApp/ Pin the order of the slots with a test that fills both: the offer above
      the first attribute row, the child sections below the last
      (`TaskEditorSlotsOrderTest`).
- [x] desktopApp/ Pin the lower half in the detail flow: the due-date row is laid out above
      the checklist section (`TaskDetailTimeTrackingSectionTest`).
- [x] desktopApp/ Keep the time-tracking guard honest after the move: the section now sits below
      the fold, so it scrolls to it before asserting it is displayed, rather than weakening the
      assertion to "is composed".
- [ ] desktopApp/ Pin the upper half of the order — a proposal drawn above the attribute rows —
      with a test that seeds a proposal. Left open on purpose: it needs a proposal-seeded
      detail screen, which is its own piece of work, and the brief for this change leaves the
      proposal placement test out of scope.
