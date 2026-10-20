# Tasks — android-test-hardening

## The floor and the verdicts

- [x] Make the instrumented floor ratchet in both directions: a run that delivers
      more than the floor is a finding, naming the direction and the numbers.
- [x] Make the floor's rewrite deliberate: refuse a drop without `--allow-drop`,
      refuse a run with skipped tests, warn on a rise, leave the prose alone.
- [x] Re-record the floor from the run that exists (7 classes / 24 tests) and
      re-record the fixture to match it.
- [x] Split `check-test-runs.py`'s verdict: no report → `EXIT_NO_RUN`; a report
      that exists and reports zero tests → the regression's exit code, with a
      message that says the report exists.
- [x] Record the decision: ADR `2026-10-20-the-instrumented-floor-is-a-ratchet.md`.

## The device tier

- [x] Decide the tier in one place — the workflow's `plan` job — and let every
      later step read that decision.
- [x] Run a one-class smoke on every push to a pull request, advisory, declared
      advisory in the step's own name.
- [x] Give the two pull-request tiers separate concurrency groups, so an
      automatic smoke run cannot cancel the labelled full run.
- [x] Skip the instrumented floor check in the smoke tier: the smoke ran one
      class on purpose, so its report is below the floor by construction.
- [x] Compile the instrumented source set on every pull request
      (`:androidApp:compileDebugAndroidTestKotlin`), in CI and in `check.sh`.
- [x] Document the label — what it is, how to apply it, how to recreate it when
      it is missing — in `docs/testing/android-instrumentation-runbook.md`.
- [x] Record the decision: ADR `2026-10-20-android-instrumented-smoke-on-every-pr.md`.

## The instrumented suite's own diagnostics

- [x] Give every instrumented class the same wiring through one base class:
      the failure bundle and the step recorder are connected once, and a class
      that forgets cannot.
- [x] Resolve the device lazily, so the two classes that do not drive the UI do
      not wait for an accessibility service they never use.
- [x] Record the *actions* — the tap, the typed text, the back press — and not
      only the waits, in the same line format the desktop recorder uses.
- [x] Cover the recorder with a test of its own: a recorder that drops half of
      what happened still produces a file.

## The Maestro flows

- [x] Build the cleanup link so the prefixes the app reads are prefixes of the
      titles that were seeded.
- [x] Sweep a failed flow's fixtures before the next flow starts.
- [x] Guard both with tests that state the property, and check that they fail
      against the old code.
- [x] Give every wait a budget that belongs to the element's kind, from one
      committed catalogue, and check the tree against it.
- [x] Record the decision: ADR `2026-10-20-the-maestro-cleanup-never-matched.md`.

## The version gate's read boundary

- [x] Move the read bound into the port, so a read that ignores cancellation
      cannot hold the caller past the deadline.
- [x] Keep the gate's own deadline, as the outer of two layers, and make it the
      port's number.
- [x] Cover the port's bound with tests, including a read that ignores
      cancellation.
- [x] State the capability: `openspec/specs/version-gate/spec.md`, and the
      index entry in `openspec/specs/MODULE-INDEX.md`.
- [x] Archive the `version-gate-read-timeout` change, and re-import the issue
      registry.

## Deliberately not done

- [ ] The search screen's behaviour for an empty query. The flow `search-clear`
      expects the idle prompt; the app shows the task list. Whether that is a
      product defect or a stale expectation is a product decision, and it is
      recorded as an issue rather than fixed here.
- [ ] Making the device tier a merge gate. An emulator is the flakiest thing in
      this CI, and a flaky gate is worse than no gate.
