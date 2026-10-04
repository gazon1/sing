# Tasks — ci-run-once-per-commit

- [ ] Confirm the before-state from the trigger graph and write down the number
      of runs a PR to `main` currently produces, per workflow. Do not cite a
      duration or a minute count — no run has executed since the Actions limit
      was exhausted, and a number from the API does not exist yet.
- [ ] Delete the `refactor/**` and `fix/**` entries from the `push` trigger in
      `ci.yml`, keeping `push: branches: [main]`. Do the same shape for
      `docs-audit.yml`, which already pushes only on `main`.
- [ ] Leave the `push`-on-`main` trigger in place. It is the only trigger that
      verifies a commit reaching `main` by rebase, fast-forward, or a merge whose
      PR was already closed.
- [x] Checked the other two workflows for the same shape — neither duplicates.
      `maestro-smoke.yml` triggers on `workflow_dispatch` and
      `pull_request: types: [labeled]`, with no `push` trigger. `maestro-nightly.yml`
      triggers on `schedule` and `workflow_dispatch`. Both are single-run per
      event, so neither needs the same edit.
- [ ] After the Actions limit is restored, confirm on a real PR that exactly one
      run per workflow appears, and record the before/after in this change's
      proposal. Until then this task is written and unverified, and the proposal
      says so.
- [ ] Do not start #100 (`ci-checks-parallel-split`) in the same change. Two
      edits to `ci.yml` at once make a green run ambiguous between them.
