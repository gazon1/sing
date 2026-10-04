# Tasks — detekt-rule-coverage-floor

- [ ] Run `just kover-rules` and record the current per-rule line coverage in
      `detekt-rules/build.gradle.kts` as a named constant, with a comment giving
      the date and the command that produced it.
- [ ] Name the uncovered rules in the same comment. A percentage with no rule
      list attached is not actionable, and "92%" reads as a grade rather than as
      a list of branches nobody has exercised.
- [ ] Set the `koverVerify` threshold *below* the measured value — the floor is
      the smallest legitimate run, not the best one. Wire it into
      `:detekt-rules:check`.
- [ ] Do not wire the root kover aggregation's `koverVerify` as part of this
      change. It covers the product modules, has its own floors in
      `config/docs/coverage-baseline.txt`, and mixing the two would make a rule
      coverage regression indistinguishable from a product coverage regression.
- [ ] Add a test that the threshold is not zero or trivially low, mirroring
      `test-coverage.py`'s existing guard: a floor that cannot fail is the same
      defect as no floor.
- [ ] Add a positive control proving `koverVerify` fails when coverage is
      dropped. Without it, a misconfigured Kover setup that instruments nothing
      reports 0% covered — which fails a high floor, and passes a zero one.
- [ ] Work the named list: add the branch tests the report calls for, or delete
      the guard if it turns out to be unnecessary. Both outcomes are progress;
      leaving the rule unchanged and the floor untouched is not.
- [ ] Raise the floor in the same commit that raises coverage, and never lower
      it without a reason recorded next to the constant.
- [ ] Close the backlog entry `detekt-rule-branch-coverage-owed` in place and
      reference this change from issue #98.
