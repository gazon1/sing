# Tasks — a-status-that-was-never-recomputed-was-reported-with-a-stale-value

- [x] Write the control first: a delegating `ProposalDao` whose `updateProposalStatus`
      throws, asserting the port receives `proposals.refresh_status_failed` and that the
      update was genuinely attempted. The second assertion is what distinguishes "the
      write failed" from "refreshStatus never ran".
- [x] Report inside `refreshStatus` with `log.e` + `crashReporter.report`. Reported, not
      thrown — the items are already decided and only the derived summary failed, so
      throwing would fail a confirm that succeeded.
- [x] Add the success-path control, so reporting unconditionally cannot pass.
- [x] Add `log` and `crashReporter` to `ProposalRepositoryImpl`, both defaulted, and wire
      them in `proposalModule()`. One construction site in production and none in tests,
      so the plumbing is a constructor and a DI line.
- [x] Record the nine false positives this change disproved, with the return type of each,
      so the next reader does not "fix" nine methods that have no `Result` to unwrap.
- [x] Closed. There is no backlog entry `refresh-status-failure-is-invisible`: it does not appear in
      `docs/decisions/deferred-backlog.md`, and `git log -S` finds the slug only in
      the commit that wrote it here. The finding came from the 2026-10-07 sweep, not
      from a backlog row — so there is nothing to close, and writing an entry now in
      order to close it next would be theatre. This change is the whole of the record.
- [ ] Out of scope: the dropped-`Result` guard. Its corpus is now empty, which is what
      makes it landable — see the guard's own change for what it must resolve and what a
      name-based heuristic costs on this tree.