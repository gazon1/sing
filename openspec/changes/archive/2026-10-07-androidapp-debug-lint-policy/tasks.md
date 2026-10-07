# Tasks — androidapp-debug-lint-policy

Executed 2026-10-05. ADR: `docs/decisions/2026-10-05-debug-source-set-is-linted.md`.

- [x] Record the findings before changing anything, so the split is measured rather
      than asserted. **It was not the recorded split: 15 findings, not 16 — 9
      formatting and 6 intentional, not 12 and 4.** The plan's own instruction to
      measure first is what caught this; the numbers in the proposal are now stale
      and the ADR carries the measured ones.
- [x] Auto-correct the 9 formatting findings (`--auto-correct`) rather than
      baselining them. Confirmed: 15 -> 6, and no formatting rule appears in the
      baseline.
- [x] Add `src/debug/kotlin` to `detekt.source` in `androidApp/build.gradle.kts`.
- [x] Baseline the remaining findings with a reason each, written about the tool
      rather than about debug code, in `ManuallySuppressedIssues` so a new finding
      cannot be absorbed by a later run. Four entries cover six findings, because
      detekt keys the signature on the expression and not the line.
- [x] Verify the baseline is load-bearing: removing one line brings four findings
      back. Both directions checked, not assumed.
- [x] Add `DetektSourceSetsAreAllScannedTest` — no source set may be missing from
      `detekt.source`.
- [x] Extend it to fail on a `source.setFrom` entry naming a directory that does
      not exist. Verified by reintroducing the original
      `src/androidAndroidTest/kotlin` typo: both assertions fire.
- [x] **Declare the module build files as task inputs.** The test above was
      written first and did not work: `:shared:jvmTest` was UP-TO-DATE after
      editing a `detekt.source` list, so it reported a verdict about a file it
      had not re-read. Same defect as `MaestroFlowTagsTest` and as the measured
      `TestTagCoverageTest` staleness. A gate that cannot be invalidated by the
      change it watches is worse than no gate, because its silence looks like a
      pass.
- [x] Record the policy in ADR form, since it generalises to every future
      `src/debug` file.
- [x] Close the backlog entry `androidapp-debug-source-set-unlinted` and
      reference this change from issue #99.

## Not done here

- `src/debug` still reads the system clock in four places and blocks a thread in
  one. Those are baselined with reasons, not fixed, because a seeder invoked from
  a deep link has to finish before the app it seeds draws anything. The seed
  model in #171 should route through an injected `Clock` rather than add a fifth.
- `DetektSourceSetsAreAllScannedTest` covers the four modules that declare a
  `detekt.source` list. A module that declares none is not counted as failing,
  because detekt then falls back to its own defaults and the list is not the
  subject of the rule.
