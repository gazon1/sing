# Plan: GitHub Issues — Agenda Feature Scope

## Sources
- GitHub issue list (`gh issue list --json number,title,state`)
- `docs/decisions/deferred-backlog.md` — reasoning and already-attempted dead ends
- `docs/decisions/2026-10-09-undoable-delete-contract.md` — canonical delete/undo contract
- Code archaeology: `SelectorTemplate.kt`, `TagsViewModel.kt`, `ProjectsViewModel.kt`,
  `CalendarFlowTest.kt`, `TestTags.kt`, `runDesktopAppTest`

---

## Issue #253 — `AgendaViewModel.handleTaskDelete` no-op
**Status: CLOSED ✅** — Fixed in `67f3c52b` (PR #274 `gazon1/refactor-agenda-ref`).

`handleTaskDelete` now calls `taskRepo.softDelete` before the snackbar; the snackbar
reports a deletion that actually happened. No further work.

---

## Issue #78 — Undo snackbar for NotesList delete
**Status: CLOSED ✅** — Implemented in PR #274.

`NotesListViewModel.handleDelete` (line 339) does `repo.delete` first, then emits
`NotesUiEvent.UndoDelete`. `handleUndoDeleteTapped` (line 371) calls `repo.restore`
and clears the pending marker **only on success**. The asymmetric failure handling
(leave the offer standing on failure) matches the `delete-safety-feedback` contract.
No further work.

---

## Issue #80 — Countdown snackbar
**Status: OPEN** — `Indefinite` snackbar, no visual countdown.

`AgendaViewModel` sets `_pendingDelete` and the snackbar is `Indefinite`. `Material3`
`SnackbarHost` has no built-in countdown animation. A custom animated snackbar would
be needed.

**Proposed fix:** Add a `SnackbarWithProgress` composable that wraps the
`SnackbarHost` content and shows a `LinearProgressIndicator` behind or alongside the
snackbar, driven by the ViewModel's `_pendingDelete` timer. This is UI work that
requires the snackbar to become a state-driven component rather than `Indefinite`.

**Effort:** Medium — needs design + implementation of a progress-aware snackbar host.

---

## Issue #107 — Regexp / DateRange selector templates missing from editor
**Status: CLOSED ✅** — `ByRegexp` and `ByDateRange` already exist in `SelectorTemplate.kt` (lines 101, 115) and are in the catalogue. No further work.

Already implemented: `ByRegexp` and `ByDateRange` exist in `SelectorTemplate.kt`
(lines 101, 115) and are in the catalogue. No decision needed.

---

## Issue #108 — FakeClock unused in desktop harness
**Status: CLOSED ✅** — Already implemented.

`runDesktopAppTest` (`DesktopAppHarness.kt:70`) accepts `clock: Clock? = null`.
`CalendarFlowTest` uses `clock = CLOCK` with `FakeClock(FIXED_NOW)` pinned to
`Instant.parse("2026-09-16T10:00:00Z")`. The `CalendarFlowTest` class-level doc
explains why a pinned instant is the correct fix (passed on Sep 30, failed Oct 1).
No further work.

---

## Issue #110 — SNACKBAR_SAVED dead code
**Status: PARTIALLY CLOSED** — Maestro flow step removed, dead code remains.

The Maestro flow step `extendedWaitUntil: id: snackbar_saved` was removed in MR-10.
However, these four artifacts of the old behavior are still present:

| File | What's left |
|---|---|
| `core/ui/TestTags.kt:508` | `const val SNACKBAR_SAVED = "snackbar_saved"` declaration |
| `core/ui/SlugTest.kt:134` | `assertEquals("snackbar_saved", TestTags.SNACKBAR_SAVED)` constant test |
| `arch/TestTagsWiringTest.kt:78` | `knownUnapplied` entry with stale reason string |
| `Maestro/TAGS.md:205` | `| SNACKBAR_SAVED | snackbar_saved | |` table row |

**Proposed fix:** Remove all four items. If a "Saved" notification toast is later
desired, `Notification.Undo` routed to `SnackbarHost` is the correct path per
`deferred-backlog.md` item 1 of "Future (not MR-10)".

**Effort:** Low — 4 deletions in separate files.

---

## Issue #104 — Tags and Projects delete have no undo/confirm
**Status: OPEN**

### Policy (from `deferred-backlog.md:764`)
- Line deletes (tasks, notes, **tags**, views, searches, attachments) → `Notification.Undo`
- Cascade / non-recoverable (project, tag group, backup file) → `ConfirmActionDialog`

### Current state
| Screen | Delete behavior | Needs |
|---|---|---|
| `NotesList` | `softDelete` + undo snackbar | ✅ Done (PR #274) |
| `TagsScreen` | Soft-delete + undo snackbar (restore implemented) | ✅ Done |
| `ProjectsScreen` | Fire-and-forget hard delete | `ConfirmActionDialog` (still open) |

### Tags: DONE
Tags are now soft-deletable with `restore()` and an undo snackbar in `TagsScreen`.
The steps above are already implemented; kept for history only.

### Projects: add ConfirmActionDialog
`ProjectsViewModel.delete` (line 122) emits `ProjectsUiEvent.Error` on failure.
No confirmation dialog on tap. The policy says cascade/non-recoverable →
`ConfirmActionDialog`. This means wrapping the delete action in a confirmation
before calling `deleteProject(id)`.

**Proposed fix:**
- Add a `ProjectsUiEvent.RequestDeleteConfirmation(project: Project)` event
- `ProjectsScreen` receives it and shows `ConfirmActionDialog`
- On confirm, dispatch `ProjectsIntent.Delete(project.id)`

**Effort:** Medium — Tags needs schema migration + repo changes + VM undo logic;
Projects needs UI dialog wiring.

---

## Summary: what is already done vs. what needs work

| Issue | Status | Action |
|---|---|---|
| #253 | CLOSED ✅ | None |
| #78 | CLOSED ✅ | None |
| #80 | OPEN | Custom animated snackbar (design + impl) |
| #107 | CLOSED ✅ | Already implemented (ByRegexp/ByDateRange exist) |
| #108 | CLOSED ✅ | None |
| #110 | PARTIALLY OPEN | Remove 4 dead-code artifacts |
| #104 | PARTIALLY OPEN | Tags done; Projects: confirm dialog (only remaining) |
