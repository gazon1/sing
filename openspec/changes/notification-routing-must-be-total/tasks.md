# Tasks — notification-routing-must-be-total

- [ ] Record every `Notification.Text` call site and its body value before
      changing the type, so "all four pass a non-null body" becomes a
      measurement rather than a claim.
- [ ] Make `ResultDialog.text` non-null and let the compiler find the call sites
      that assumed otherwise. Expect zero hits; a non-zero count means the audit
      above was wrong, which is worth knowing before the type changes.
- [ ] Add the host-level test: a test that drives the routing, not a test per
      call site. It must fail if a new variant is added without a branch, which
      is the case per-call-site tests cannot cover.
- [ ] Verify the routing test fails when the host is given a null body through a
      back door. If the type is non-null that is now a compile error, and the
      test's job is to prove the type is doing the work rather than to re-check
      it.
- [ ] Walk the delete paths in `NotesListScreen.kt`, `TagsScreen.kt` and
      `ProjectsScreen.kt`. For each: is it reversible, and which pattern applies?
      Record the list before editing — the count is the acceptance measure.
- [ ] Apply `Notification.Undo` to the reversible ones, following
      `TaskDetailContent.kt:81`.
- [ ] Apply the confirmation from `TagGroupsScreen.kt:92` to the irreversible
      ones. Do not batch a multi-select delete behind one dialog that hides which
      selected items are at risk.
- [ ] Add the check for a new list screen: any delete path with neither affordance
      is a failure, naming file and action.
- [ ] Write the ADR for the saved-acknowledgement decision (snackbar vs dialog)
      before touching `SNACKBAR_SAVED`. It is a product call, and it lands in the
      same code as the routing change, so an unrecorded decision here is one
      someone will re-litigate.
- [ ] Resolve `SNACKBAR_SAVED` in the same commit as that ADR: either give it a
      call site or remove it from `TestTags.kt:273` and from `knownUnapplied` at
      `TestTagsWiringTest.kt:78`. Do not leave it in a third state — a tag that
      is neither used nor removed reads as live to the next person.
- [ ] Delete the stale `knownUnapplied` reason text, which describes a Maestro
      flow that no longer exists. An allowlist entry carrying a false explanation
      is worse than none, because it reads as verified.
