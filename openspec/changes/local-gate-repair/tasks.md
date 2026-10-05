# Tasks — local-gate-repair

## Make it run

- [ ] Confirm the before-state rather than trusting this description:
      `./gw :shared:tasks --all | grep -i kover` and `./gw tasks --all | grep -i kover`. Record
      both outputs in the PR. The claim is that only the root project registers the report tasks.
- [ ] In `coverage-ratchet`, replace `:shared:koverXmlReport` with the root `koverReport`, which
      is what CI uses and what the root `build.gradle.kts` comment says is correct by
      construction — it also puts the test tasks in the graph.
- [ ] Keep the `rm -rf shared/build/kover shared/build/reports/kover` wipe. Kover merges binary
      reports incrementally, and the ratchet floors are only reproducible from clean state. The
      wipe path may need to change if the report moves to the root project — check where
      `koverReport` writes, and point `scripts/coverage-ratchet.py` at the same file CI uploads
      (`build/reports/kover/report.xml`).
- [ ] Replace `./gradlew` with `./gw` in the recipes touched, so the gate uses the same wrapper
      as the rest of the project. Do not sweep unrelated recipes in the same change.
- [ ] Run `just cr` end to end. A gate that is edited but never executed has been changed, not
      fixed — that is the state this issue was filed from.

## Leave one authority

- [ ] Make `gate` step 2/4 call the `lint` alias instead of re-listing detekt tasks, so
      `:androidApp` and `:detekt-rules` cannot be dropped from one copy again.
- [ ] Check whether the three scripts `gate` does not run belong there. #116 records four gate
      scripts that no gate invokes; `gate` already runs `check-detekt-registrations.sh`, so
      decide deliberately for the other three rather than by omission. Cross-link that issue
      rather than closing it here.

## Verify

- [ ] In `gate` step 4, replace `just gate-maestro` with `just tests::gate-maestro`. Same
      unqualified-name defect as `just cr` above, in the one call site that fix missed:
      the recipe lives in the `tests` module, so from `gate` the bare name does not
      resolve. Found 2026-10-05 — `just gate` completed three green steps and then died
      with `justfile does not contain recipe 'gate-maestro'` before reaching the flows.
- [ ] `just gate SKIP_MAESTRO=1` reaches step 4 and reports the Maestro flows as skipped. That
      run does not verify the flows, and the recipe says so — keep that honest.
- [ ] Note the runtime. `coverage-ratchet` runs the full instrumented suite, which the recipe's
      own comment measured at 9m27s on 2026-10-04. If that makes `gate` impractical, say so in
      the PR; do not quietly leave a gate nobody runs.
