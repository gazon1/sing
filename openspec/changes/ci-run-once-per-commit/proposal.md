# ci-run-once-per-commit

Issue: #117 · Related: #100 (`ci-checks-parallel-split`, deferred)

## What

Make one commit produce one CI run per workflow, so that "this run" and "the
previous run" have unambiguous referents.

## Why

Measured at `953b802e`:

```
ci.yml:15         push:          branches: [main, "refactor/**", "fix/**"]
ci.yml:34         pull_request:  branches: [main]
docs-audit.yml:12 push:          branches: [main]
docs-audit.yml:14 pull_request:  branches: [main]
```

Commit `a249aded` produced three runs. Two are the `ci.yml` shape — any push to a
`refactor/**` or `fix/**` branch runs CI on `push` and again on the
`pull_request` for the same SHA. The third is `docs-audit.yml` running on
`pull_request` and then again on the `push` to `main` that closed it.

The minutes are the cheap half. The expensive half is that
`test-execution-integrity` REQ-1 and REQ-3 read the current run's own results, and
REQ-5 compares this run against the previous run's artifact. Three concurrent
runs on one SHA make the previous run ambiguous, and branch protection then keeps
whichever verdict it picked.

This is currently structural rather than observed. All jobs fail at step 0 with
`The job was not started because recent account payments have failed`, so no run
has executed since the Actions minute limit was exhausted — the three runs are
inferred from the trigger graph, not counted from three green runs. Say so in
any measurement of the fix: the before-state is a reading of `ci.yml`, not a
number from the Actions API.

## The two real options

1. **`pull_request` only, plus `push` on `main`.** Delete the
   `refactor/**`/`fix/**` entries from the `push` trigger. Every PR gets one run
   from `pull_request`; every commit that lands on `main` gets one from `push`.
   Cost: a commit that reaches `main` without a PR — rebase, fast-forward, a
   merge whose PR was already closed — is still verified, which is the property
   worth keeping.
2. **Keep both triggers, guard the `push` one.** Add
   `if: github.event_name != 'pull_request'` to the `pull_request` job, or an
   `if:` on the `push` job that skips when an open PR already exists for the SHA.
   Cost: the skip condition is a second source of truth about which run counts,
   and it is the kind of condition that silently stops skipping.

Recommendation: 1. It is a deletion, and a deletion has no condition to drift.

## What not to do

- Do not remove the `push` trigger outright. That leaves rebases and
  fast-forwards unverified, and it is the change that looks like a simplification
  while removing coverage.
- Do not bundle this with #100. That change moves steps between jobs; this one
  changes how many times a job starts. Doing both at once makes a green run
  ambiguous between the two edits.
- Do not claim a measured time saving until a run has actually executed. The
  Actions limit is currently exhausted, so any before/after number would be
  fabricated.
