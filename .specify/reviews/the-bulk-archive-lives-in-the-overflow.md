---
change: the-bulk-archive-lives-in-the-overflow
verdict: pass
date: 2026-11-11
pr: https://github.com/gazon1/sing/pull/2849
reviewed: 473153a53c7e2162d454e870256a8b621c61d246
documents: sha256:3578fe396a98de98ba78d6853ae87a32bb0b4ff50816b17af78c9b5f51b8dbf2
---

## What was reviewed

The Archive bulk action: the overflow entry and confirmation, the ViewModel
count and write path, the repository's archivable count projection, the fake's
`observeAll` contract, the desktop flow test, and the Android Maestro flow. The
review was made against the change commit; the verdict and the flow fix were
added after it, and they do not change the contract documents.

## What was found, and what was done about it

- The confirmation's count was not asserted in the desktop test. The test now
  reads the confirm button (`Archive 1 task`), so a dialog that drops the count
  fails.
- A zero-result sweep was silent while the spec said it reports. The ViewModel
  now emits `NothingToArchive`, the screen says "Nothing to archive", and the
  spec has the scenario.
- The count loaded every live task with its tags and edges. It is now a
  repository `COUNT(*)` projection, pinned by the contract test against both
  the fake and Room.
- The Android task claimed a run that had not happened. The Maestro flow ran on
  the device (`just tm Maestro/flows/archive/02-bulk-archive-confirm.yaml`), and
  the success dialog is dismissed before the final row assertion.
- The fake's `observeAll()` returned archived rows. It now matches `watchActive`;
  the fidelity test and the proposal use-case test read the trash where they
  need an archived row.
- A single archived task read "Moved 1 tasks". It is now reported in the
  singular.
- The archive Maestro flows repeated the menu → Archive block. One
  `open-archive` helper serves them.
- The review gate was green because the change was baselined. This verdict
  replaces that entry.

The refresh gesture on the Archive screen still archives without a
confirmation; it is an explicit open follow-up in the ADR, not a defect of this
change.
