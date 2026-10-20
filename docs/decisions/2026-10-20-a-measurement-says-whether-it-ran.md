---
title: "A measurement has to say whether it ran"
date: 2026-10-20
status: accepted
tags: [ci, measurement, tooling]
---

## Context

The nightly flake-rate measurement has failed on every night it has run. Six
runs, six times: 2026-10-15 (#632), 10-16 (#661), 10-17 (#710), 10-18 (#715),
10-19 (#737), 10-20 (#754).

Each of those nights it opened an issue titled **"Nightly flake-rate
measurement found an unacknowledged flake"**, whose body was a link to the run.
No flake was ever found, because no measurement ever happened.

The cause is one line of the workflow's setup. `flake.yml` never installs the
gate tooling, and the measuring script imports the gate package, which imports
`loguru`:

```
File "scripts/check-flaky-tests.py", line 50, in <module>
    from sgate.commands.base import run
  ...
ModuleNotFoundError: No module named 'loguru'
```

The second cause is the step that opens the issue:

```yaml
- name: Open issue on unacknowledged flake
  if: ${{ failure() && github.event_name == 'schedule' }}
```

The measuring script exits non-zero for **two different things**: the rate
exceeded the ceiling (a finding about a test) and the measurement could not be
completed (a finding about the measurement). `failure()` cannot tell them
apart, so the issue was opened for both — and the title it opened claimed the
first.

## Idea

- **A.** Install the tooling and leave the condition as it is. The measurement
  runs; a finding opens the issue; a crash also opens it, with a title that
  says "found an unacknowledged flake" — and the reader goes looking for a
  flaky test that was never measured.
- **B.** Install the tooling and open the issue only when the measurement
  *completed*. The distinction has to come from somewhere the exit code cannot
  carry.
- **C.** Drop the nightly. It has never produced a result, so it has never
  earned its runner minutes.

## Decision

**B**, and the "somewhere" is a **verdict file the measuring script writes**.

- `scripts/measure-flake-rate.py` takes `--verdict-file` and writes a
  machine-readable verdict: `state` (`clean` / `found` / `broken`), the rate,
  the totals, and the ids involved. It writes it **before** it exits, on every
  path — including the paths where it exits 1.
- `flake.yml` installs the gate tooling (the package alone; the linters are
  the expensive part and this job needs none), runs the measurement with
  `--verdict-file`, and reads the verdict back as a step output.
- **The issue steps read the verdict, not `failure()`.** A measurement that
  found an unacknowledged flake opens the flake issue, whose body now names the
  ids — the previous body was the run URL alone, so the reader had to open the
  run and dig. A measurement that did not run opens a *different* issue, with
  its own title: "the nightly flake-rate measurement did not run".
- A clean measurement opens nothing and the job is green.

The six false issues are closed with a comment saying what they were.

## Rationale

- **The exit code was never the measurement's verdict.** It is the *job's*
  verdict: "this did not succeed". The measurement has three outcomes and the
  job has one. A caller that reads one and reports the other publishes
  something it did not measure — and the title is what a reader believes.
- **A nightly that always fails is a nightly nobody reads.** That is the
  workflow's own stated reason for opening an issue instead of leaving a red
  cron run. It cuts both ways: an issue that opens every night with the same
  false claim is worse than a red cron run, because it looks like a signal.
  Six identical issues in a row is the shape of a broken measurement, and the
  reader has to learn to ignore it — which is the exact outcome the issue was
  opened to prevent.
- **The verdict belongs to the script, not to the caller.** The script knows
  which of its three situations it is in. A caller that re-derives the
  distinction from the exit code and the log is re-deriving something the
  script already knows, and will get it wrong the same way.
- **The broken case is a real case.** "The tests did not run" and "the tests ran
  and the rate exceeded the ceiling" both mean the measurement is unusable, but
  they send the reader to different places: one to the test job, the other to
  a flaky test. Merging them sends the reader to the wrong one.
- **C was not on the table.** The measurement is worth having: it is the only
  surface that reports a flake *rate* rather than a boolean flip
  (`2026-10-04-measurement-integrity`), and the boolean is what the two-run
  comparison in `ci.yml` can see. A measurement that has never run is not
  evidence that the measurement is worthless — it is evidence that the
  measurement was broken.

## Consequences

- **The nightly measures again.** If a test really is flaky, the issue names it.
- **A broken measurement is now visible as a broken measurement.** The issue
  says "did not run" and gives the reason, so the reader fixes the measurement
  instead of hunting a ghost.
- **The verdict is a file, and files can go missing.** A measurement that dies
  before it writes the verdict leaves no file, and the workflow reads that as
  `broken` — which is the right default: no verdict means nothing was
  measured.
- **The same shape exists elsewhere.** `check-test-runs.py` now distinguishes
  "no report" from "a report below the floor" by exit code, and the CI step
  that consumes it must say the same. A measurement that reports a boolean
  where it could report a magnitude should report the magnitude; a measurement
  that reports a verdict it did not measure should report nothing.
- **The six closed issues were not flake reports.** If a reader was tracking
  one of them as a known-flaky test, that tracking was wrong, and the baseline
  in `config/docs/flaky-baseline.txt` was not the reason it was never
  acknowledged.

## Links

- `scripts/measure-flake-rate.py` — the verdict it writes
- `.github/workflows/flake.yml` — the two issue steps, and the tooling it
  installs
- `scripts/tests/test_measure_flake_rate.py` — the three verdicts, and the
  distinction between "clean" and "broken"
- `config/docs/flaky-baseline.txt` — the acknowledged flakes
- `2026-10-04-measurement-integrity.md` — the generalisation this applies
