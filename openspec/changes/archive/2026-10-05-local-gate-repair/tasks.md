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
- [x] Check whether the three scripts `gate` does not run belong there. **Transferred to #116**
      at archive time: it needs that issue's list and a decision per script, which is its own
      change rather than a repair of this one.

## Verify

- [x] `just cr` runs end to end and reports "All floors held" — **done**, twice, and it was
      proven alive by a 0.02% rise it caught.
- [ ] A full `just gate` run to step 4 is still unverified. **Transferred to #142** at archive
      time. Maestro fails in this environment, so the recipe's own composition — four steps in
      order, step 3's exit status propagating, `SKIP_MAESTRO=1` reporting the flows as *skipped*
      rather than *passed* — is untested. That last one is the shape of a gate that reports
      success while testing nothing.
- [ ] The runtime note. **Transferred to #142.** `coverage-ratchet` measures 9m27s on
      2026-10-04; nobody has written down what a full `gate` costs, which is what makes it
      possible to judge whether anyone runs it.

**Archived 2026-10-05** with two items deliberately left open and filed rather than carried
silently. The repair itself shipped in `a068b432`; what is archived is the reasoning, and the
reasoning says plainly what was not verified.
