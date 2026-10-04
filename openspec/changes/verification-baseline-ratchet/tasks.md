# Tasks — verification-baseline-ratchet

- [ ] Record the current detekt suppression count as a committed floor
      (`config/detekt/baseline-count.txt`, 413 today) with a header explaining
      that the number is a ceiling, not a target.
- [ ] Extend `scripts/build-version-catalog-gate.py` or add a sibling gate that
      compares the current `baseline-shared.xml` + `baseline-desktopApp.xml`
      finding count against that floor and prints the delta (not just ok/fail).
- [ ] Add the gate to `check.sh` and to the lint CI job. It must be blocking.
- [ ] Write a `--update-baseline-count` path that raises the floor only, and
      refuses to lower it.
- [ ] Make the floor-lowering path of `check-coverage.py --update-baseline` and
      `check-test-runs.py --update-baseline` require a reason: a non-empty
      string recorded in the baseline file next to the value.
- [ ] Add a self-test for the "gate refuses to lower" behaviour — the same
      positive-control discipline `TestTagCoverageTest` and
      `EntityMapperCompletenessTest` already use, so the gate cannot pass by
      finding nothing.
- [ ] Run `python3 -m unittest discover -s scripts/tests` and confirm the new
      tests fail against a deliberately lowered floor before they pass.
