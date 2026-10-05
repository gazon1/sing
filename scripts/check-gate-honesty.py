#!/usr/bin/env python3
"""Prove the lint gate is actually measuring, by planting a violation and reading the report.

## Why this exists

Three defects in one session, all the same shape, all found by hand rather than by a gate:

- **#136** — a stale `:detekt-rules` classpath made `:shared:detekt` report **green with no custom
  rules running**. Observed three times, twice as a false pass.
- **#137** — `:shared:detektBaseline` runs without the custom rule set, so regenerating the
  baseline deleted every custom-rule entry (357 → 338) and the next `detekt` run then reported a
  clean tree *because nothing was running*.
- **#138** — a coverage number that may not describe any execution at all.

The common failure is not "the check is wrong". It is **a measurement that cannot be
distinguished from its own failure.** A rule that stopped running and a codebase that stopped
violating produce the same green; a baseline that lost its entries and a baseline that never had
any produce the same diff.

The cure is the same in all three cases and it is cheap: **plant a violation, assert it is
reported, and run that before trusting a green.**

`RuleFiresSmokeTest` is the existing rule-level version of this. It is not enough on its own,
because it constructs the rule directly — which is precisely what keeps passing when the *rule
classloader* is the broken part. This script goes through the real `detekt` task, the real
plugin classpath, and the real report file.

## What it does, and what it refuses to do

For each probe: write a temporary file containing a known violation, run the real detekt task,
assert the report contains the expected rule id, then **restore the file unconditionally**.

It never leaves the tree modified, and it never reports success on its own evidence — a probe that
does not fire is a failure, not a pass, because a probe that cannot fail is the thing this script
exists to catch.

## Why it is slow, and why that is fine

Each probe is a full `:shared:detekt` run (~1.5 min). Running all of them costs minutes, which is
why this is a `just` recipe and not part of `check.sh`: the person who needs it is the one about
to regenerate a baseline or trust a green lint run, and they need it *then*, not on every commit.

The probes are additive, so `--only NAME` runs one.
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLEW = ROOT / "gw"

# A file under commonMain that no rule inspects structurally, so adding a probe to it cannot
# change any other rule's verdict. The package is real so the code would compile if it stayed.
PROBE_DIR = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/observability"


class Probe:
    """One planted violation, and what the report must then say about it."""

    def __init__(self, name: str, rule_id: str, body: str, why: str) -> None:
        self.name = name
        self.rule_id = rule_id
        self.body = body
        self.why = why

    @property
    def path(self) -> pathlib.Path:
        return PROBE_DIR / f"GateHonestyProbe.{self.name}.kt"


PROBES = [
    Probe(
        name="appErrorCode",
        rule_id="AppErrorCode",
        body=(
            "package com.singularity.todo.core.observability\n"
            "\n"
            "import com.singularity.todo.core.error.AppError\n"
            "\n"
            "/** TEMPORARY probe planted by scripts/check-gate-honesty.py. */\n"
            "fun gateHonestyProbe() {\n"
            '    throw AppError.Validation("planted by the gate-honesty check")\n'
            "}\n"
        ),
        why=(
            "a custom rule that stopped loading. The whole point of this probe is that it is a "
            "CUSTOM rule: it fails if the plugin classpath is stale or missing, which is #136's "
            "exact failure mode and the one that produces a silent green."
        ),
    ),
    Probe(
        name="unwiredReporter",
        rule_id="NoUnwiredReporterInBinding",
        # The probe declares its OWN ViewModel-shaped class rather than naming a real one.
        # The first version pointed at `TaskTimeSlot`, which is not ViewModel-shaped, so the
        # rule correctly stayed quiet and the probe reported DID NOT FIRE — the script working
        # as designed, and a reminder that a probe pointing at the real codebase rots the day
        # someone renames a class. A probe must fail only for the reason it exists.
        body=(
            "package com.singularity.todo.core.observability\n"
            "\n"
            "import org.koin.core.module.dsl.viewModel\n"
            "\n"
            "/** TEMPORARY probe planted by scripts/check-gate-honesty.py. */\n"
            "class GateHonestyProbeViewModel(val id: Int)\n"
            "\n"
            "fun gateHonestyProbe() {\n"
            "    viewModel { GateHonestyProbeViewModel(get()) }\n"
            "}\n"
        ),
        why=(
            "the second half of the crash-reporting invariant, added after two production "
            "bindings shipped a no-op reporter. A gate that only proves the *first* rule in a "
            "set loads would not notice a rule added alongside it."
        ),
    ),
]

REPORT = ROOT / "shared/build/reports/detekt/detekt.md"


def run_detekt() -> tuple[int, str]:
    """Run the real detekt task. Returns (exit code, combined output)."""
    proc = subprocess.run(
        [str(GRADLEW), ":shared:detekt", "--console=plain"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    return proc.returncode, (proc.stdout + proc.stderr)[-4000:]


def probe_once(probe: Probe) -> tuple[bool, str]:
    """Plant, run, read, restore. Returns (fired, detail)."""
    backup = probe.path.with_suffix(".kt.bak")
    pre_existing = probe.path.exists()
    if pre_existing:
        shutil.copy2(probe.path, backup)
    elif REPORT.exists():
        # A stale report would be read as this probe's evidence if detekt did not rewrite it.
        REPORT.unlink()

    try:
        probe.path.write_text(probe.body, encoding="utf-8")
        code, output = run_detekt()

        if not REPORT.exists():
            return False, "detekt wrote no report at all"
        report = REPORT.read_text(encoding="utf-8", errors="replace")
        if probe.rule_id not in report:
            return False, (
                f"the report does not mention {probe.rule_id}. "
                f"detekt exit={code}. This is the failure the check exists for: the rule is not "
                f"running, so a green result means nothing. "
                f"If you just changed detekt-rules/, try `./gw --stop` first — see #136."
            )
        return True, f"{probe.rule_id} reported the planted violation"
    finally:
        if pre_existing:
            shutil.move(str(backup), str(probe.path))
        elif probe.path.exists():
            probe.path.unlink()
        # Leave no report claiming a violation that no longer exists.
        if REPORT.exists():
            REPORT.unlink()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument(
        "--only",
        action="append",
        help="run one probe by name (repeatable); default runs all",
    )
    ap.add_argument("--list", action="store_true", help="list probe names and exit")
    args = ap.parse_args()

    if args.list:
        for p in PROBES:
            print(f"{p.name}\t{p.rule_id}")
        return 0

    selected = [p for p in PROBES if not args.only or p.name in args.only]
    if not selected:
        print(f"no probe matches {args.only}", file=sys.stderr)
        return 2

    print("=" * 72)
    print("GATE HONESTY — can the lint gate tell a clean tree from a broken one?")
    print("=" * 72)

    failures = 0
    for probe in selected:
        print(f"\n→ {probe.name}  (expects {probe.rule_id})")
        print(f"  {probe.why}")
        started = time.monotonic()
        fired, detail = probe_once(probe)
        took = time.monotonic() - started
        if fired:
            print(f"  FIRED — {detail}  ({took:.0f}s)")
        else:
            failures += 1
            print(f"  DID NOT FIRE — {detail}  ({took:.0f}s)")

    print("\n" + "=" * 72)
    if failures:
        print(f"FAILED: {failures}/{len(selected)} probe(s) did not fire.")
        print("A probe that cannot fire is the defect this script exists to catch, so this is a")
        print("failure of the *check*, not a pass with nothing to report.")
        return 1
    print(f"OK: {len(selected)}/{len(selected)} probes fired. The gate is measuring.")
    print("A green `just lint` from here is evidence rather than an absence of evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
