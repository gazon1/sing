# Tasks — a-save-that-wrote-the-note-but-not-its-links-is-not-a-save

- [x] Write the control first: `setOutgoingLinksOverride` returns `Result.failure`,
      the editor saves, and the state must carry an error. Record it failing against
      the current code — confirmed, `expected: not <null>`.
- [x] Append `.getOrThrow()` to `setOutgoingLinks`, matching the two writes above it.
- [x] Add the success-path control, so an unwrap on the wrong call — or one that fired
      on every save — could not pass.
- [x] Record in the code comment that outgoing links are not synced, so the next reader
      does not re-classify this as a sync defect. The dropped-`Result` audit did, and
      `SyncedWriteEnqueuesTest` is the counter-evidence.
- [x] Closed. There is no backlog entry `note-editor-drops-outgoing-links`: it does not appear in
      `docs/decisions/deferred-backlog.md`, and `git log -S` finds the slug only in
      the commit that wrote it here. The finding came from the 2026-10-07 sweep, not
      from a backlog row — so there is nothing to close, and writing an entry now in
      order to close it next would be theatre. This change is the whole of the record.
- [ ] Out of scope, deliberately: the other dropped writes this audit found are their
      own defects — `ProfileBootstrapper` (returns a profile id that was never
      activated), 9 settings writes in `CalendarSyncViewModel` / `SyncViewModel`, and
      3 × `proposals.refreshStatus`. This change is one function.
- [ ] Still to come: the `arch/` guard for a dropped `Result` generally. It cannot be
      switched on while any of the above are live, because a rule that lands red is
      worse than no rule.