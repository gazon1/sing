---
title: "Undo snackbar needs its own window, and popup testTags need re-asserting"
status: accepted
date: 2026-10-04
tags: [ui, compose, accessibility, testing, maestro]
---

# Context

Found while running the Maestro smoke pass that closes out the
`navigation-open-policy` epic (see `2026-10-04-navigation-policy`). Two defects,
both invisible to the unit/desktop test suites, showed up only on a real device:

1. **The undo toast never appeared.** Deleting a task from the detail screen
   produced a correctly laid-out, correctly timed `Snackbar` that no user could see.
   `NotificationHost` composed the `SnackbarHost` as an in-window sibling of the
   screen content, and it lost the z-order fight twice over: the content's opaque
   background is drawn after it (the host is composed *before* the content), and the
   shell-level FAB is drawn after the whole entry. `Maestro/tasks/06-delete-undo.yaml`
   was asserting on a `text: "Undo"` node that existed in the semantics tree while
   being painted under two opaque layers.

2. **Test tags declared inside popups were unreachable from Android UI automation.**
   `DropdownMenu` and `ModalBottomSheet` render into their own platform window with
   their own semantics root. The app-root `testTagsAsResourceId = true` installed by
   the shell does not reach into a popup, so every `testTag` declared inside one was
   invisible to Maestro, which addresses Android UI nodes by resource id.

A third, smaller finding came out of the same pass: the task-detail overflow rows had
no stable ids of their own, so flows had to select them by their localized label.

# Idea

The shared widget layer has two structural problems, not two cosmetic ones:

- A toast is a *transient overlay* competing with the shell's own chrome, and Compose
  gives no z-order guarantee between siblings. Only a separate window escapes the
  question.
- `testTagsAsResourceId` is a property of a semantics *root*, and a popup is its own
  root. The mapping therefore has to be re-asserted per popup.

# Decision

**1. The undo toast renders in its own `Popup` window.** `NotificationHost` keeps its
full-screen `Box` as the anchor and places a `SnackbarHost` inside a
`Popup(focusable = false)`, positioned bottom-centre of the anchor bounds by a small
`PopupPositionProvider`. The position is byte-for-byte the place the in-window snackbar
occupied, so the visual design does not move; only the compositing changes. Dialog
notifications were already separate windows and are untouched.

**2. The notification queue is cleared *after* `showSnackbar` returns, not before.**
The presenting effect is keyed on `notification`; nulling the field up front changes
that key, which cancels the very next recomposition's `showSnackbar` call and the
toast never reaches the screen at all. This is the second half of the same bug and is
the reason the naive fix (clear-then-show) produces a *worse* symptom than the
original.

**3. `Modifier.mapTestTagsAsResourceIds()` is an expect/actual re-assertion for
popups.** `expect fun Modifier.mapTestTagsAsResourceIds(): Modifier` in
`core/ui/TestTagResourceId.kt`; the android actual sets
`semantics { testTagsAsResourceId = true }`, and the desktop actual is a deliberate
no-op because desktop automation reads test tags straight out of the semantics tree.
Apply it at popup content roots (dropdown menus, bottom sheets).

**4. The task-detail overflow rows carry `TestTags.EditorOverflow.{ARCHIVE, DELETE,
RESTORE}`.** Selecting a menu row by its localized label breaks the moment the label
is translated or reworded; the flows now address ids. `EditorOverflow.PIN` /
`UNPIN` remain unwired debt and stay in `TestTagsWiringTest`'s `knownUnapplied`
allowlist.

# Rationale

The alternative to (1) — re-ordering the content and host so the snackbar draws last
— cannot work: the FAB is not in the same composition, it is drawn by the shell around
the whole entry, so no amount of sibling reordering wins. A separate window is the only
mechanism Compose offers for "above everything the entry draws".

(3) deserves the explicit note that the *shell* already sets the mapping. Adding a
per-popup call is not redundancy: `testTagsAsResourceId` is read from the semantics node
of the enclosing root, and a popup's content has a different root. Without the
re-assertion, ids declared in popups are published to the test-tag registry but cannot
be produced — the "declared but never applied" trap that ADR
`2026-09-30-testtag-registry-honesty` exists to prevent, one level deeper.

Both (1) and (2) are the kind of change that a reviewer cannot re-derive by reading the
diff: the diff shows a `Popup` and a moved statement, not the two independent causes of
the invisible toast.

# Consequences

- `NotificationHost` now opens a platform window per toast. Popup content is not
  part of the parent's composition, so anything that used to observe it via parent
  state must go through the host state — which it already did.
- Focusable is explicitly `false` so the toast never steals IME focus from the editor
  it is undoing.
- The Maestro flows that select an overflow row by label were updated to ids; flows
  that waited for a snackbar on a screen that pops back were corrected to reflect the
  real behaviour (the toast is hosted by the detail screen, so deletion does not pop
  back by itself).
- Popup-window content must call `mapTestTagsAsResourceIds()` explicitly. A new popup
  that forgets it is a silent automation gap; `MaestroFlowTagsTest` and the tag
  registry are the places to extend when that becomes a pattern worth enforcing.
- This ADR documents work that was found *during* the navigation epic's verification
  and is committed with it. It is a UI fix, not navigation, and the commit is kept
  separate so the two reviews do not interfere.

# Links

- ADR `2026-09-30-testtag-registry-honesty` — the tag registry's honesty contract
- ADR `2026-10-04-navigation-policy` — the epic whose verification surfaced this
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/NotificationHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTagResourceId.kt`
- `Maestro/tasks/06-delete-undo.yaml`
