---
change: the-bulk-archive-lives-in-the-overflow
verdict: pass
date: 2026-11-11
pr: https://github.com/gazon1/sing/pull/2849
reviewed: 6fac785a5ee485a5570bcc4bb11a3be02251313a
documents: sha256:00a0c5841f8a3731a04f57a380218529188909d09bf5700a619f003a5666a12d
---

## What was reviewed

The Archive bulk action: the overflow entry and confirmation, the ViewModel
count and write path, the repository's archivable count projection, the fake's
`observeAll` contract, the desktop flow test, and the Android Maestro flow. The
review was made against the fix commit; the verdict and the grill findings were
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
  the device, and the success dialog is dismissed before the final row
  assertion.
- The fake's `observeAll()` returned archived rows. It now matches
  `watchActive`; the fidelity and proposal tests read the trash where they need
  an archived row.
- A single archived task read "Moved 1 tasks". It is now reported in the
  singular.
- The archive Maestro flows repeated the menu block; one `open-archive` helper
  serves them.
- The first CI run found two gate findings: the new helper was missing from
  `Maestro/CONVENTIONS.md`, and this review was not written down on the PR.
  Both are fixed.

The Grill review then found:

- The confirmation was hidden, not closed, when the eligible count reached
  zero; a later completed task brought the old dialog back. The top bar clears
  the confirmation on zero, and a zero → positive regression test covers it.
- The Archive screen's pull-to-refresh still ran the shared refresh, whose first
  step is the unconfirmed bulk archive. The screen no longer uses
  `RefreshableContent`; the other screens keep the gesture, and the spec and ADR
  record the decision.
- The Maestro flow's ADR reference was wrong; corrected.
- A failed sweep's guard reset is now pinned by `ArchiveViewModelTest`.

The shared dialog, the count projection, the repository API, the fake parity
change, the ViewModel concurrency guard, and the test architecture are
unchanged.
