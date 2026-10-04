# Tasks — androidapp-debug-lint-policy

- [ ] Record the 16 findings as a list before changing anything, so the
      12-to-fix / 4-to-suppress split is measured rather than asserted.
      `./gradlew :androidApp:detekt` with `src/debug/kotlin` temporarily added to
      `source.setFrom` produces it.
- [ ] Auto-correct the 12 formatting findings
      (`BlankLineBetweenWhenConditions`, `ClassSignature`, and the rest) rather
      than baselining them. Re-run detekt and confirm the count drops to 4.
- [ ] Add `src/debug/kotlin` to `detekt.source` in `androidApp/build.gradle.kts`
      so the source set is scanned.
- [ ] Baseline the 4 remaining findings with a reason each, written about the
      tool rather than about debug code. A suppression justified by "this is
      debug code" is the blanket exemption wearing a suppression's syntax, and
      it will be copy-pasted into production where it is not true.
- [ ] Add a test asserting no production source set is missing from
      `detekt.source` — this is the class of defect that let `src/debug` go
      unlinted, and it is invisible: detekt scans what it is told to scan and
      reports nothing about what it was not told to scan.
- [ ] Extend that test to fail on a `source.setFrom` entry naming a directory
      that does not exist, which is how `src/androidAndroidTest/kotlin` survived
      in the list.
- [ ] Verify `:androidApp:detekt` still passes with the baseline in place, and
      that it fails when a baseline line is removed.
- [ ] Record the policy in ADR form — it generalises to every future `src/debug`
      file, which is the test in this repository for whether something is a
      decision or a chore.
- [ ] Close the backlog entry `androidapp-debug-source-set-unlinted` in place and
      reference this change from issue #99.
