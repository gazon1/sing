# the-bulk-archive-lives-in-the-overflow — tasks

**Issue:** #2744 · **Spec:** `tasks` (The bulk archive is a confirmed overflow
action)

Read first: ADR `2026-11-11-the-bulk-archive-lives-in-the-overflow.md`.

---

## 1. The ViewModel owns the count and the write

- [x] shared/ — Observe the repository's archivable count alongside the archive
      list and expose it in the screen state.
      **Test:** `ArchiveViewModelTest` — the eligible count excludes active and
      already archived tasks.
- [x] shared/ — Read the eligible count as a repository projection, not by loading
      every live task with its tags and edges.
      **Test:** `TaskRepositoryContractTest` — the count is pinned against both the
      in-memory fake and the Room implementation.
- [x] shared/ — Rename the intent to describe the sweep and allow one write at a
      time.
      **Test:** `ArchiveViewModelTest` — a second confirmation while the sweep
      runs calls the repository once.
- [x] shared/ — A successful sweep reports the repository's count, a sweep that
      moved nothing says so, and a failure reaches the error event and the
      reporter.
      **Test:** `ArchiveViewModelTest` — the success event carries the count; the
      zero-result sweep emits the neutral event; a single task is reported in the
      singular; the failure event and the reporter entry are present.
- [x] shared/ — A failed sweep clears the in-flight guard, so the action is
      available again.
      **Test:** `ArchiveViewModelTest` — a failed sweep leaves `archiving` false
      and the next dispatch reaches the repository.

## 2. The screen offers the confirmed overflow action

- [x] shared/ — Remove the full-width button, add the overflow item and the
      shared confirmation dialog, and disable the item at zero and while a
      sweep runs.
      **Test:** `ArchiveBulkActionFlowTest` — the item is disabled when no
      completed live task exists.
- [x] shared/ — Cancel leaves the task live; Confirm archives it.
      **Test:** `ArchiveBulkActionFlowTest` — the repository's archived state is
      read after each action.
- [x] shared/ — The confirmation closes when the eligible count reaches zero and
      does not reopen when eligibility returns.
      **Test:** `ArchiveBulkActionFlowTest` — the dialog is dismissed by a
      zero count, and a later eligible task does not bring it back.
- [x] shared/ — The Archive screen no longer uses the shared refresh container,
      whose first step is the unconfirmed bulk archive. The other screens keep
      the gesture.
      **Test:** the screen has no `RefreshableContent`; the spec scenario "A pull
      on the Archive screen does not archive".
- [x] shared/ — Update the empty-state copy and remove the button from the
      preview.
      **Test:** `Maestro/flows/negative/04-empty-archive.yaml` still reaches the
      empty state and the overflow item is present.

## 3. Android reachability

- [x] androidApp/ — The action is reachable through the shell's top bar on the
      Archive screen.
      **Test:** `Maestro/flows/archive/02-bulk-archive-confirm.yaml` — Cancel
      leaves the task in Inbox; Confirm moves it to the archive.

## 4. Review

- [x] shared/ — A separate agent reviews the diff before the PR is opened and
      files the verdict under `.specify/reviews/`.
      **Test:** the review-verdict gate reads the verdict for this change.
