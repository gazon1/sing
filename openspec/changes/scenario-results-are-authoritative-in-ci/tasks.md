# Tasks — scenario-results-are-authoritative-in-ci

Each task that changes behaviour names a test that verifies it.

- [ ] Decide and write down what "this build claims the target" means — the
      targets the specs list, or the targets this run actually attempted. The two
      requirements in the delta are unimplementable until that is pinned, and
      guessing it is how the "un-covered target" case turns into a false red.
- [ ] `infra/kiwi/traceability/` — emit, per target, whether it was *attempted*,
      not merely whether a result directory existed. The current signal cannot
      distinguish "ran and produced nothing" from "was never run", and that
      distinction is the whole requirement. Covered by
      `ZeroTestcaseRuleScope` in `scripts/tests/test_traceability.py`.
- [ ] `infra/kiwi/traceability/` — fail when a scenario claims an **attempted**
      target and no result exists for the build's own commit, naming the scenario
      and the target. Covered by a new test asserting the failure message names
      both.
- [ ] `infra/kiwi/traceability/` — render an un-attempted target as explicitly
      out of scope rather than as an outcome, so a cell cannot be misread as
      "verified". Covered by a rendering test asserting the two states differ in
      the output.
- [ ] **Prove the check can fail.** Withhold one claimed target's result, run the
      gate, and record that it exits non-zero and names the scenario and target.
      Restore it and record the green run. Both belong in the check's own comment
      — a gate documented only with its happy path is the artefact this
      requirement exists to prevent.
- [ ] Resolve #150 — decide whether the flow results move into the job that
      builds the matrix, whether that job publishes its own, or whether the
      un-covered case is simply stated. Whatever is chosen, the matrix header has
      to say which, so a permanent empty column is not read as a coverage hole.
- [ ] `check.sh` **and** `.github/workflows/ci.yml` — wire the new check into
      both. CI does not run `check.sh`; it restates gates inline, so a check that
      lives only in `check.sh` runs only on a maintainer's machine. This has
      already bitten once.
- [ ] `scripts/tests/` — a regression test asserting the check is reachable from
      both entry points, so a future move of the gate cannot quietly drop one.
