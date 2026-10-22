# Tasks — ci-fast-only-pr-full-on-schedule

## 1. The population is decided once, and read everywhere

- [ ] 1.1 Add `types: [opened, synchronize, reopened, labeled]` to the
      `pull_request` trigger of the CI workflow — without it in `types`, a
      `pull_request` event fires only on opened/synchronize/reopened, so the
      label alone would never start a run.
- [ ] 1.2 Add the "Select test population" step to the JVM tier: `fast` for a
      pull request without the label, `fast,slow` for a pull request with it and
      for the schedule and the manual trigger, `fast` for a push.
- [ ] 1.3 Read the label from BOTH the `labeled` event's label name and the
      pull request's label list — a label added to an open pull request has to
      start a run on its own AND keep applying to the runs that follow.
- [ ] 1.4 Add the validation step: the decided value has to be one of the known
      populations, or the tier stops.

## 2. Every invocation reads that decision

- [ ] 2.1 Replace the literal selector with the decided variable in all six
      Gradle invocations of the JVM tier — the test run and the five Kover
      invocations.
- [ ] 2.2 The scenario normaliser passes `--partial` on a fast-only run: every
      scenario carrier is a slow class, so on a fast population the answer to
      "did the carrier run?" is "no" by construction. The full population keeps
      the rule.

## 3. A floor per population

- [ ] 3.1 Read the population from the same property the tests use, so there is
      one source of truth.
- [ ] 3.2 Measure the fast population's coverage on the same set of tasks the
      tier runs — a floor measured on a different set is unreachable.
- [ ] 3.3 Record the measured values with their date, and check each floor
      against its own measurement.

## 4. The slow population is checked, not assumed

- [ ] 4.1 The build records the population it measured, so the gate does not
      have to infer it.
- [ ] 4.2 The gate compares the declared slow classes against the reports
      whenever the run measured the slow population, and fails with the class
      name.

## 5. The scanner answers the same question the Kotlin gate answers

- [ ] 5.1 The scanner resolves an aliased `@Tag` import from the import itself,
      the way the Kotlin gate already does.
- [ ] 5.2 A test proves the two agree on which files alias, and that no class in
      an aliased file reads as untagged.

## 6. The repository stops claiming a CI selection that no longer exists

- [ ] 6.1 Update the notes that assert the nightly selection for every CI run:
      the project instructions, the contribution guide, the testing document,
      the traceability recipe, the count-floor baseline and its generator.
- [ ] 6.2 Update the tag-strategy skill's CI section — it described a workflow
      that does not exist, and it described the wrong one.

## 7. Verify

- [ ] 7.1 `openspec validate --all --json --strict` exits 0.
- [ ] 7.2 The population parity gate is green: one variable, six invocations.
- [ ] 7.3 The count floors are green in the fast mode.
- [ ] 7.4 The coverage floors are green in BOTH modes.
- [ ] 7.5 A broken slow class fails the nightly check BY NAME.
- [ ] 7.6 An empty population value fails the validation step.
