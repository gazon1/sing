# Tasks — a-profile-that-was-never-activated-was-reported-as-activated

- [x] Write the control first, with `FakeProfileRepository.switchToFailure` so a failed
      switch is reachable at all. Record it failing against the current code — confirmed.
- [x] One control per outcome sharing the `if`: switched, not-requested, not-found. A fix
      that made every non-null `activateName` throw would pass a single control and break
      two legitimate ones.
- [x] Append `.getOrThrow()`, and add `@throws` to `run` so the contract is on the
      signature rather than only in a comment.
- [x] Record in the comment *why* the id is load-bearing — `mcp/Main.kt:164` migrates
      rows with it. Without that sentence the next reader sees a best-effort activation
      and may "fix" the throw by returning null, which is the version that cannot be
      told apart from a run that never asked for a switch.
- [x] Closed. There is no backlog entry `profile-bootstrapper-reports-a-failed-switch`: it does not appear in
      `docs/decisions/deferred-backlog.md`, and `git log -S` finds the slug only in
      the commit that wrote it here. The finding came from the 2026-10-07 sweep, not
      from a backlog row — so there is nothing to close, and writing an entry now in
      order to close it next would be theatre. This change is the whole of the record.
- [ ] Out of scope: the dropped-`Result` guard is still red on this site until it lands,
      which is the reason it goes before the guard.
- [ ] Follow-up worth its own change, not this one: `mcp/Main.kt:175-179` swallows every
      bootstrap failure with "Non-fatal: the rest of the server can still operate against
      the personal/default profile". After this fix an agent started with
      `--profile=ai-agent` whose switch failed keeps running against the personal
      profile — and writes there. That is a real hazard, but it is a decision about the
      host's failure policy, not a dropped Result, and it belongs where the owner can see
      it argued rather than smuggled in with a one-line unwrap.