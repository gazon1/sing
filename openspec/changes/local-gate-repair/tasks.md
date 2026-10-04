# Tasks — local-gate-repair

## Make it run

- [x] Confirm the before-state rather than trusting this description:
      Confirmed: only the root project registers `koverReport` / `koverXmlReport` /
      `koverHtmlReport`; `:shared` has none, and the settings-level Kover plugin registers
      them on the root.
- [x] In `coverage-ratchet`, replace `:shared:koverXmlReport` with the root `koverReport`, which
      is what CI uses and what the root `build.gradle.kts` comment says is correct by
      construction — it also puts the test tasks in the graph.
- [x] Keep the kover wipe. It had to **widen**, though: the old one removed only `shared`'s two
      directories, and the aggregate accumulates every module's binary report, so a partial
      wipe leaves a number that depends on what happened to be left over. It is now a
      `find … -name kover -prune -exec` across all modules. `DEFAULT_REPORT` in
      `scripts/coverage-ratchet.py` moved to `build/reports/kover/report.xml` to match where
      `koverReport` writes and what CI uploads.
- [x] Replace `./gradlew` with `./gw` in the recipes touched, so the gate uses the same wrapper
      as the rest of the project. Did not sweep unrelated recipes in the same change.
- [x] Run `just cr` end to end — and it *held*, which is the whole point. Re-baselining raised
      **every** floor, because the old ones were pinned to a blind spot the aggregate does not
      have: the agenda floor's own note admitted the Compose subtrees it excluded were covered
      by desktopApp flow tests "which kover cannot see", and now it can. The "whole shared
      module" label was a lie and is now "whole codebase". The ratchet was then proven alive by
      a 0.02% rise it caught from the `EventBus` change in the same batch.

## Leave one authority

- [x] Make `gate` step 2/4 call the `lint` alias instead of re-listing detekt tasks, so
      `:androidApp` and `:detekt-rules` cannot be dropped from one copy again.
- [ ] Check whether the three scripts `gate` does not run belong there. **Left open
      deliberately** — it needs #116's list and a decision per script, which is its own change
      rather than a repair of this one. Cross-link that issue rather than closing it here.

## Verify

- [ ] `just gate SKIP_MAESTRO=1` reaches step 4 and reports the Maestro flows as skipped. That
      run does not verify the flows, and the recipe says so — keep that honest.
- [ ] Note the runtime. `coverage-ratchet` runs the full instrumented suite, which the recipe's
      own comment measured at 9m27s on 2026-10-04. If that makes `gate` impractical, say so in
      the PR; do not quietly leave a gate nobody runs.
