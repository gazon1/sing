# Tasks — detekt-rule-coverage-floor

- [x] Run `just kover-rules` and record the current per-rule line coverage in
      `detekt-rules/build.gradle.kts` as a named constant, with a comment giving
      the date and the command that produced it.
      Recorded as `DETEKT_RULE_BRANCH_FLOOR = 60` against 65.1% (627/963 branches,
      2026-10-10, `just tkr`) — *branch* coverage, not line: a guard that never
      fires is the defect this measures, and the comment says why the unit moved.
- [x] Name the uncovered rules in the same comment. A percentage with no rule
      list attached is not actionable, and "92%" reads as a grade rather than as
      a list of branches nobody has exercised.
      Head of the list is in the comment (worst ten by missed branches, from the
      2026-10-15 re-measurement: 65.6%, 725/1105, 34 of 60 rule classes with at
      least one missed branch). The generator is `just tkr`'s branch section,
      added the same day — a list with no generator is a list that goes stale
      while staying plausible.
- [x] Set the `koverVerify` threshold *below* the measured value — the floor is
      the smallest legitimate run, not the best one. Wire it into
      `:detekt-rules:check`.
      `reports { total { verify { rule { bound { minValue = … } } } } }` in
      detekt-rules/build.gradle.kts; `./gradlew :detekt-rules:check --dry-run`
      lists `:detekt-rules:koverVerify`, so `verify.onCheck`'s default already
      wires it into `check`.
- [x] Do not wire the root kover aggregation's `koverVerify` as part of this
      change. It covers the product modules, has its own floors in
      `config/docs/coverage-baseline.txt`, and mixing the two would make a rule
      coverage regression indistinguishable from a product coverage regression.
      Untouched: the only verify rule in the tree is this module's.
- [x] Add a test that the threshold is not zero or trivially low, mirroring
      `test-coverage.py`'s existing guard: a floor that cannot fail is the same
      defect as no floor.
      `check-gate-wiring.py` Part C (`kover_verify_rules_without_usable_floor`,
      `_KOVER_MIN_FLOOR = 60`) plus seven tests in
      `scripts/tests/test_check_gate_wiring.py`, including one that points the
      check at the real repository.
- [x] Add a positive control proving `koverVerify` fails when coverage is
      dropped. Without it, a misconfigured Kover setup that instruments nothing
      reports 0% covered — which fails a high floor, and passes a zero one.
      Automated, not recorded: the SABOTAGE control
      `detekt-rule-coverage-floor` in `scripts/check-gate-wiring.py` raises
      `DETEKT_RULE_BRANCH_FLOOR` to 100 — by pattern, with an assert that the
      substitution actually landed — and requires `:detekt-rules:koverVerify`
      to exit non-zero. Verified 2026-10-15: baseline rc=0, sabotaged rc=1
      (`Rule 'detekt-rules branch coverage' violated: branches covered
      percentage is 65.610900, but expected minimum is 100`), file restored
      byte-identical. The *declaration* half — a zero, absent or unresolvable
      bound — stays with the Part C tests above; the two together are the whole
      control, and the reasoning is in
      `docs/decisions/2026-10-15-kover-floor-lives-in-the-build-file.md`.
- [ ] Work the named list: add the branch tests the report calls for, or delete
      the guard if it turns out to be unnecessary. Both outcomes are progress;
      leaving the rule unchanged and the floor untouched is not.
- [x] Raise the floor in the same commit that raises coverage, and never lower
      it without a reason recorded next to the constant.
      The rule is written into the constant's own comment, which is the only
      place a reader lowering it would have to pass through.
- [ ] Close the backlog entry `detekt-rule-branch-coverage-owed` in place and
      reference this change from issue #98.
