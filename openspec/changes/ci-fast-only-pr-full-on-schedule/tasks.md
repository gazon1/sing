# Tasks — ci-fast-only-pr-full-on-schedule

## 1. The population is decided once, and read everywhere

- [x] 1.1 Add `types: [opened, synchronize, reopened, labeled]` to the
      `pull_request` trigger of the CI workflow — without it in `types`, a
      `pull_request` event fires only on opened/synchronize/reopened, so the
      label alone would never start a run.
- [x] 1.2 Add the "Select test population" step to the JVM tier: `fast` for a
      pull request without the label, `fast,slow` for a pull request with it and
      for the schedule and the manual trigger, `fast` for a push.
- [x] 1.3 Read the label from BOTH the `labeled` event's label name and the
      pull request's label list — a label added to an open pull request has to
      start a run on its own AND keep applying to the runs that follow.
- [x] 1.4 Add the validation step: the decided value has to be one of the known
      populations, or the tier stops.

## 2. Every invocation reads that decision

- [x] 2.1 Replace the literal selector with the decided variable in all six
      Gradle invocations of the JVM tier — the test run and the five Kover
      invocations.
- [x] 2.2 The scenario normaliser passes `--partial` on a fast-only run: every
      scenario carrier is a slow class, so on a fast population the answer to
      "did the carrier run?" is "no" by construction. The full population keeps
      the rule.

## 3. A floor per population

- [x] 3.1 Read the population from the same property the tests use, so there is
      one source of truth.
- [x] 3.2 Measure the fast population's coverage on the same set of tasks the
      tier runs — a floor measured on a different set is unreachable.
- [x] 3.3 Record the measured values with their date, and check each floor
      against its own measurement.

## 4. The slow population is checked, not assumed

- [x] 4.1 The build records the population it measured, so the gate does not
      have to infer it.
- [x] 4.2 The gate compares the declared slow classes against the reports
      whenever the run measured the slow population, and fails with the class
      name.

## 5. The scanner answers the same question the Kotlin gate answers

- [x] 5.1 The scanner resolves an aliased `@Tag` import from the import itself,
      the way the Kotlin gate already does.
- [x] 5.2 A test proves the two agree on which files alias, and that no class in
      an aliased file reads as untagged.

## 6. The repository stops claiming a CI selection that no longer exists

- [x] 6.1 Update the notes that assert the nightly selection for every CI run:
      the project instructions, the contribution guide, the testing document,
      the traceability recipe, the count-floor baseline and its generator.
- [x] 6.2 Update the tag-strategy skill's CI section — it described a workflow
      that does not exist, and it described the wrong one.

## 7. Verify

- [x] 7.1 `openspec validate --all --json --strict` exits 0.
- [x] 7.2 The population parity gate is green: one variable, six invocations.
- [x] 7.3 The count floors are green in the fast mode.
- [x] 7.4 The coverage floors are green in BOTH modes.
- [x] 7.5 A broken slow class fails the nightly check BY NAME.
- [x] 7.6 An empty population value fails the validation step.

## 8 — what was measured, and where

Every number below is a run on this branch on 2026-10-22, not an expectation.

- **The population decision** (1.2, 1.4): the validation step's own shell was
  extracted from `ci.yml` and run with each value (measured 2026-10-23 on the
  branch that names the population) —
  `fast` → exit 0, `full` → exit 0, `""` → exit 1
  (`unknown TEST_POPULATION=''`), `slow` → exit 1, `FAST` → exit 1.
  The step asks `config/test-populations.properties` whether the name is one of
  the known ones; the file is the list of names, not the step.
- **The fast population** (2.1, 3.2, 3.3, 7.3): `./gw :koverVerify -Ptest.tags=fast`
  → BUILD SUCCESSFUL in 37m 25s. Measured 27.73 instruction / 23.23 branch / 31.26
  line; the floors are 27 / 23 / 31. The run was taken with the raw tag lever; the
  population `fast` maps to the same tags
  (`config/test-populations.properties`: `fast=fast`), so the two spellings
  select the same classes.
  Re-measured on 2026-10-23 on the branch that ships this change:
  `./gw :koverReport -Ptest.population=fast` → BUILD SUCCESSFUL in 35m 46s, and
  the report holds **4735 classes over 579,002 instructions** — measured 28.78
  instruction / 23.92 branch / 32.06 line. More classes than the full run below
  held on 2026-10-22, because the tree grew in between: the fast report is not
  a fraction of the universe, it is the same universe on a later tree.
- **The full population** (7.4): `./gw :koverVerify -Ptest.tags=fast,slow`
  → BUILD SUCCESSFUL in 33m 23s, with `:koverVerify` executed rather than
  up-to-date. Measured 55.88 / 40.44 / 57.23 against the floors 27 / 20 / 27.
  The run manifest recorded `tags=fast,slow` and 440 executed classes. The
  population that names this run is `full` (`full=fast,slow`). Its report holds
  4642 classes over 565,144 instructions.
- **The denominator is the report's ROOT counters.** The number this change
  published at first — "2.82M instructions" for both runs — was the sum of every
  `INSTRUCTION` counter in the Kover XML, so it read five times the report's own
  denominator; the percentages were unaffected, because a ratio is the same at
  every level. The figures above are the root counters of the two reports, and
  they are recorded in `config/docs/coverage-measurements.properties` — the file
  the repository publishes its measurements from. Nothing compared a published
  number with the artifact it was read from, and that is how the wrong
  denominator lived through the whole change: `scripts/check-published-measurements.py`
  compares them now.
- **The count floors** (7.3): `check-test-runs.py --require shared:jvmTest,
  desktopApp:test,mcp-server:test,shared:testAndroidHostTest` → "Test run floors
  met" — shared:jvmTest 440 classes / 3689 tests, desktopApp:test 62 classes /
  197 tests, mcp-server:test 7 classes / 39 tests, shared:testAndroidHostTest
  225 classes / 2097 tests, 0 skipped everywhere.
- **The slow classes are checked, not assumed** (4.2, 7.5): a slow class's JUnit
  report was removed from the results of a run whose manifest said
  `tags=fast,slow`, and the gate failed with exit 1 and the class's name:
  `shared:jvmTest: 1 @Tag("slow") class(es) declared in the sources produced no
  JUnit report, although this run selected the slow population:
  PlatformPragmasTest`. With the report restored, the same command is green.
- **The spec** (7.1): `openspec validate ci-fast-only-pr-full-on-schedule --json
  --strict` → 1 item, 1 passed, 0 failed.
- **The population parity gate** (7.2) and the repository's own gates:
  `scripts/ci/static-gates.sh` → 40 passed, 0 failed;
  `python3 -m unittest discover -s scripts/tests` → 1748 tests, OK.

## 9. A population is a name, and the report counts what it measures

- [x] 9.1 `config/test-populations.properties` — the one file that maps a
      population name to the tags it selects. Read by the build (the root build
      script hands every module the tag list), by the settings script (which
      picks the floors by population) and by the gates (which compare what a
      job's invocations declared).
- [x] 9.2 `-Ptest.population=fast|full` in the CI workflow, in every Gradle
      invocation, and in the settings script. `-Ptest.tags` still works and
      still wins; passing both is an error, not a preference.
- [x] 9.3 The run manifest is written by the root build script for EVERY
      module's test task, and records the population as well as the tags.
      Measured: `:mcp-server:test -Ptest.population=fast` wrote
      `population=fast`, `tags=fast`, `executedClasses=5`.
- [x] 9.4 `:adr-core:jvmTest` joins the `koverReport` list — the module holds
      the app's ADR rules, so its classes were in the denominator while its
      tests never ran. `:adr-cli` is excluded from the coverage scope with
      `skipProjects`: it is build tooling, and it is not built during the
      coverage job.
- [x] 9.5 `scripts/check-coverage-task-list.py` — every project with JVM tests
      is either in the `koverReport` list or excluded from the coverage scope.
      The probe removes `:adr-core:jvmTest` from the list, and the gate says so.
- [x] 9.6 `scripts/check-coverage-floor-declaration.py` — the app floors are
      declared in `settings.gradle.kts`, and no other build script declares a
      verify block under the name of the app floor. The probe puts a verify
      block back into the root build script, and the gate says so.
- [x] 9.7 The instrumented coverage run is priced: `config/docs/
      coverage-cost.properties` holds the measured cost per population, the
      published page carries it, and `:koverVerify` prints the estimate before
      it starts.
- [x] 9.8 Both Kover readers print the class count, which is the number that
      says WHICH report this is: the fast population's report of 2026-10-23
      holds 4735 classes over 579,002 instructions, and the full population's
      report of 2026-10-22 holds 4642 classes over 565,144.

## 10. The published numbers are compared with the measurements

- [x] 10.1 `config/docs/coverage-measurements.properties` — the record of the
      measurements, per population: the class count, the instruction total, the
      branch and line totals, the three measured percentages and the day each
      was measured. Read out of the reports, not typed by hand.
- [x] 10.2 `scripts/check-published-measurements.py` — every class count and
      instruction total the repository publishes is compared with the record.
      The probe changes one published number, and the gate names the file, the
      line and both numbers.
