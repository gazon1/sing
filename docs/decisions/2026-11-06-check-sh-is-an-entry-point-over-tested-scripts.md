---
title: "check.sh is an entry point over tested scripts, and the device lane is its own hook"
date: 2026-11-06
status: accepted
tags: ["tooling", "gates", "ci"]
supersedes: 2026-11-05-check-sh-is-an-hk-pipeline
---

# check.sh is an entry point over tested scripts, and the device lane is its own hook

## Context

The ADR of 2026-11-05 moved the local loop's steps out of bash and into `hk.pkl`.
What it left behind was the shell that wrapped them: ninety-six lines of
`check.sh` that installed mise, mapped a flag to a profile, exported the timing
record and — before this decision — ran the device steps inline.

Three things were still true of that shell, and none of them was visible in the
loop's verdict:

- **The logic had no tests.** The device steps (adb instrumentation, Maestro
  flows) and the JUnit summary were bash: a serial-number choice, an opt-in
  switch and an XML parse, with nothing that could say what they did.
- **The device steps were in the wrong file.** They need hardware, they are
  opt-in, and their failure is not the loop's verdict — yet they sat in the
  script that *is* the loop's verdict.
- **The timing record was translated by hand.** `check.sh` exported
  `HK_TIMING_JSON` and a hand-written converter turned it into
  `name<TAB>milliseconds` for `sgate gate-costs`. hk already writes the JSON;
  the converter was a second format of the same facts, with its own test.

## Idea

- Let each nontrivial piece of logic be a small Python script with a unit test,
  and let the shell and the hk config be the thin callers.
- Give the device work its own hk hook (`device`), so it is declared in the
  same place as everything else but runs only when asked.
- Read hk's timing record as it is, instead of translating it.

## Decision

`check.sh` is an entry point and nothing else. It installs mise, maps the flag
to a profile, exports `HK_TIMING_JSON` to `build/diagnostics/cycle-steps.json`
(the fast lane writes `fast-lane-steps.json`), runs `hk check --all --profile
"$LANE"`, and prints `./sg gate-costs`. The device steps are gone from it.

The device work is the `device` hook in `hk.pkl`: three sequential steps —
`scripts/android-instrumentation.py`, `scripts/check-instrumented-test-floor.py`
and `scripts/maestro-flows.py` — run by `hk run device`. It is not part of
`hk check`, so CI never runs it, and it is not part of any profile.

The opt-in switches stay inside the scripts (`SKIP_ADB=1`, `RUN_MAESTRO=1`):
hk's `Step.condition` cannot see environment variables, and a hook step that
fails because nobody has a device attached is a broken hook, not an honest
report.

The cost record has one format. hk writes `HK_TIMING_JSON`; `sgate gate-costs`
reads that JSON directly. The converter is deleted.

The JUnit summary is `scripts/print-test-failures.py`, and
`scripts/ci/print-test-failures.sh` stays as a thin shim so that CI paths and
`hk.pkl` do not move.

`just` grows the three recipes a person actually wants: `tests::check-fast`,
`tests::check-device` and `tests::cost`.

## Rationale

**A script that only calls other scripts is a place to look, not a place to
read.** `check.sh` went from 720 lines to 96 without losing a check, and every
line it kept is a line someone has to hold in their head before running the
loop.

**Logic that cannot be tested is logic that cannot be trusted.** The serial
number the instrumentation step picks, the "no report at …" message, the list
of failed tests — all of them are decisions, and a decision without a test is a
guess that happens to be in a shell.

**The device lane is a different verdict.** It needs hardware, it is slow, it
fails for reasons the loop does not care about, and it is not something CI
should run. Keeping it in `check.sh` meant the loop's entry point and the
device's entry point were the same file with a switch between them.

**One format of the same facts.** The converter existed because the reader
wanted a table; but the reader can have the table from the JSON, and a second
format is a second thing to keep in step. hyperfine was considered and
rejected: it times commands, not the steps inside an hk hook, so the per-step
breakdown of the loop's cost — the thing the report exists for — would be lost.

## Consequences

- `./check.sh`, `./check.sh --fast` and `./check.sh --affected` behave as
  before; the steps are the same commands in the same order.
- The device tier is run with `hk run device` (or `just tests::check-device`).
  `SKIP_ADB=1` runs the instrumentation step without touching a device, which
  is how the wiring is exercised on a machine that has none.
- The cost report reads hk's own record. The published baseline and
  `docs/testing/gate-costs.md` are unchanged, because the step names did not
  change.
- `scripts/android-instrumentation.py`, `scripts/maestro-flows.py` and
  `scripts/print-test-failures.py` are named without the `check-` prefix, so
  the gate registry, the tools catalogue and the reachability and liveness
  gates do not see them as gates.
- The `device` hook names its steps in `hk.pkl`, which is where
  `check-gate-wiring.py` looks for a Gradle task or a gate script that the loop
  runs. A device step that stopped being declared would be reported.

## Links

- `check.sh`, `hk.pkl`, `.just/tests/mod.just`
- `scripts/android-instrumentation.py`, `scripts/maestro-flows.py`,
  `scripts/print-test-failures.py`
- `scripts/tests/test_android_instrumentation.py`,
  `scripts/tests/test_maestro_flows.py`,
  `scripts/tests/test_print_test_failures.py`
- `scripts/src/sgate/commands/gate_costs.py`
- `docs/testing/gate-costs.md`, `docs/testing/android-instrumentation-runbook.md`
- `docs/decisions/2026-11-05-check-sh-is-an-hk-pipeline.md` (superseded)
- `docs/decisions/2026-10-21-the-cost-of-the-loop-is-measured.md`
