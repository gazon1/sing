# Tasks — a-write-tool-reports-the-write-it-did-not-perform

- [ ] Add `createOverride` / `updateOverride` to `FakeProjectsRepository` and
      `FakeTagsRepository`. `FakeTaskRepository` and `FakeNotesRepository`
      already carry them under the "Configurable results (for failure-path
      tests)" heading; these two are the gap that would otherwise force the
      control to assert on the happy path only.
- [ ] Write the controls **before** the fix: one test per tool, seven in all.
      Each sets the repository's `create`/`update` to `Result.failure`, invokes
      `execute`, and asserts it fails with that repository's exception. Run them
      against the unfixed code and record the failure — a control that has never
      been seen red is not a control.
- [ ] Append `.getOrThrow()` to the seven calls in `Create{Note,Project,Tag,Task}Tool`
      and `Update{Note,Project,Task}Tool`.
- [ ] Confirm the seven controls pass and that the existing `WriteToolsTest`
      happy paths are unchanged — the DTOs must serialise identically.
- [ ] Note in the KDoc of at least `CreateNoteTool` why the unwrap is there, so
      the next author adding an eighth write tool sees the convention at the
      site rather than only in this change.
- [x] Closed. There is no backlog entry `ai-tools-drop-a-failed-write`: it does not appear in
      `docs/decisions/deferred-backlog.md`, and `git log -S` finds the slug only in
      the commit that wrote it here. The finding came from the 2026-10-07 sweep, not
      from a backlog row — so there is nothing to close, and writing an entry now in
      order to close it next would be theatre. This change is the whole of the record.
- [ ] Out of scope, recorded rather than silently dropped: the remaining 25
      dropped-`Result` sites the audit found (18 `syncRepository.enqueue`, 3
      `proposals.refreshStatus`, `ProfileBootstrapper`, 2 in
      `CreateTaskFromDraft`, `NoteEditor.setOutgoingLinks`). Each becomes its own
      change; this one covers only Class A.