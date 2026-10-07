# Tasks — enqueue-failure-is-reported-at-the-seam

- [ ] Write the control first: drive `outboxDao.insert` into throwing through a
      delegating wrapper (the `UnreadableOutbox` shape already in
      `SyncEngineStorageFailureTest`), and assert the crash reporting port
      received the original throwable under `sync.enqueue_failed`. Record it
      failing against the current code.
- [ ] Report in `SyncEngine.enqueue`: `log.e` plus `crashReporter.report`. Keep the
      `Result.failure` return unchanged — the control asserts both halves, because
      "it returns failure" and "someone finds out" are different claims.
- [ ] Assert the negative: a successful enqueue reports nothing, so a rule that
      reported unconditionally cannot pass by reporting everything.
- [ ] Do **not** add a cancellation branch. `runCatchingResult` rethrows
      `CancellationException` before it can reach a `Result`, so a visible failure
      is always a real error. Adding the branch would be a guard against something
      the type already excludes.
- [ ] Update `Note`'s KDoc, which currently records the swallow as a historical
      fact: it says enqueue "swallows it via runCatchingResult", which is no
      longer the whole story.
- [ ] Record the 18 call sites as unchanged in the proposal, so a later reader
      does not read their silence as an oversight rather than as the decision.
- [ ] Close the backlog entry `enqueue-failure-is-silent`.
- [ ] Out of scope, recorded rather than silently dropped: `proposals.refreshStatus`
      (3), `ProfileBootstrapper` (1), `CreateTaskFromDraft` (2),
      `NoteEditor.setOutgoingLinks` (1), and the dropped-`Result` gate itself.