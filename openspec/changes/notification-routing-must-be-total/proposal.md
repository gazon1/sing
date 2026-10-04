# notification-routing-must-be-total

Issues: #103, #104, #110 · Backlog entries: `notification-text-null-invisible`,
`delete-without-confirm-or-undo`, `saved-views-crud-flow-selects-a-snackbar-that-does-not-exist`

## What

Make every `Notification` the app emits produce exactly one visible response, and
apply the app's existing delete policy to the surfaces that never got either
affordance.

## Why

**The host is not total.** `NotificationHost.kt:112` routes every
`Notification.Text` to `ResultDialog`, and `ResultDialog.kt:18` opens with
`if (text == null) return`. A caller passing a null body gets no dialog, no
snackbar and no error — the composable runs and renders nothing.

No user-visible case is broken today. All three sites the original entry named
are fixed, and the four remaining `Notification.Text` call sites pass non-null
bodies. The defect is the shape: the next caller who writes
`Notification.Text("Deleted")` gets a success path with no output, and the build
stays green.

That is why a test per call site is the wrong instrument. The property belongs to
the host, and the host is where it should be pinned.

**The delete policy is applied inconsistently.** Reversible deletes emit
`Notification.Undo`; irreversible ones ask first. `TagGroupsScreen.kt:92-112`,
`TaskDetailContent.kt:81`, `SavedAgendaScreen.kt:171` and `DiscardChangesDialog.kt:23`
follow it. `NotesListScreen.kt`, `TagsScreen.kt` and `ProjectsScreen.kt` contain
neither — verified zero occurrences of each in all three.

**The acknowledgement was never built.** Saving a saved-agenda view produces no
visible response at all, which is what `SNACKBAR_SAVED` was declared for.
`TestTags.kt:273` still declares it and `TestTagsWiringTest.kt:78` still lists it
in `knownUnapplied` — with a reason describing a Maestro flow that no longer
exists. An allowlist entry carrying a false explanation is worse than none,
because it reads as verified.

## How

Make `ResultDialog.text` non-null so the compiler rejects the combination at
every call site, rather than adding a branch to the host. Runtime `return`
becomes a compile error, and the type says what the host can actually show.

The delete work is classification, not invention: walk the delete paths in the
three files, decide reversible or irreversible, and apply the pattern that
already exists in the reference implementations. No new dialog, no new host.

The saved-acknowledgement decision belongs in an ADR — it is a product call
between a snackbar and a dialog, and it is entangled with the routing change
above, so the two should land together or the second will be done twice.
