# android-test-hardening

Issue: — · Related: `docs/decisions/2026-10-20-android-instrumented-smoke-on-every-pr.md`,
`docs/decisions/2026-10-20-the-instrumented-floor-is-a-ratchet.md`,
`docs/decisions/2026-10-20-the-maestro-cleanup-never-matched.md`,
`docs/decisions/2026-10-20-version-gate-read-timeout.md`

## What

The Android test tiers got their measurements, and every measurement turned out to
be measuring the wrong thing. This change fixes the four places where that happened
and closes the two gaps the fixes exposed.

1. **The instrumented floor was behind the suite.** `config/docs/instrumented-test-floor.txt`
   recorded five classes and nineteen tests while the tier delivered six and
   twenty-one. The gate compared 21 against 19, found no drop, and stayed green: a
   floor that understates reality is not a weak floor, it is a floor that has stopped
   asking its question. The floor now ratchets in both directions, and raising it is
   a deliberate act.

2. **"Nothing ran" and "the run breached the floor" were one verdict.** A required
   source set with no report and a required source set whose report says zero tests
   are different facts with different remedies, and `check-test-runs.py` reported both
   as a regression. The no-run case keeps its own exit code; a report that *exists*
   and reports zero tests is a regression, and the message says so.

3. **The device tier only ran on a timer.** Between two nightlies, a pull request
   that broke the app on a device was indistinguishable from one that did not, and
   the instrumented source set was compiled by nothing in the pull-request pipeline
   at all. The tier now runs a one-class **smoke** on every push to a pull request —
   advisory, never a merge gate — and the instrumented sources are compiled on every
   pull request. The full suite stays where it was: on the nightly, on `main`, on
   tags, on manual dispatch, and on request through the `run-device-tests` label.

4. **The Maestro cleanup never matched anything.** The nightly of 2026-10-20 failed
   43 flows across all four shards, and the screenshots showed tasks carrying other
   flows' run ids. The cleanup link was built as `f"'{run_id},Buy%20milk'"` — shell
   quotes and a pre-encoded space — and then URL-quoted again, so the app read the
   prefixes `"'r4797858390"` and `"Buy%20milk'"`. The app matches titles by prefix,
   so neither matched: every fixture a flow seeded stayed on the device for the rest
   of the shard. The link is built correctly now, and a failing flow's fixtures are
   swept before the next flow starts rather than for the rest of the shard.

5. **The Maestro waits had no common budget.** 471 waits carried eleven different
   timeouts, and the same element was waited for with 5 s, 8 s, 10 s, 15 s and 30 s
   in different flows. The budget is now a property of the *kind* of element, read
   from one catalogue, and the gate checks that every wait in the tree agrees with
   its kind.

6. **The version gate's read bound lived in the caller.** A deadline in a caller can
   only end a cooperative read; a read that blocks inside a driver call ignores
   cancellation, and the caller's deadline expires with the read still in flight.
   The port now bounds its own reads, and the gate's deadline and the port's bound
   are the same number.

## What not to do

- **Do not make the device tier a merge gate.** An emulator is the flakiest thing in
  this CI, and a flaky gate teaches people to ignore it. The smoke is advisory and
  says so in its own name.
- **Do not let the floor regenerate itself.** A floor that rewrites itself is a
  mirror of the thing it measures. Raising it stays deliberate, and lowering it
  needs `--allow-drop` and a reason.
- **Do not wipe the app's data between Maestro flows.** `clearState: true` would
  destroy the session the emulator snapshot carries, and every flow would land on
  the sign-in screen. The isolation is per-flow and selective.
- **Do not let the port's bound and the gate's deadline drift apart.** They are the
  same constant.

## Impact

- Affected capabilities: `test-execution-integrity` (modified), `version-gate`
  (modified).
- Affected code: `scripts/check-instrumented-test-floor.py`, `scripts/check-test-runs.py`,
  `scripts/check-ui-wait-budgets.py`, `scripts/src/emulator/maestro/**`,
  `shared/src/commonMain/kotlin/com/singularity/todo/core/config/**`,
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/gate/**`,
  `androidApp/src/androidTest/**`, `Maestro/**`, `.github/workflows/**`.
- No product behaviour changes, except one that is not a behaviour change: the
  version gate's read is now bounded by the port as well as by the gate.
