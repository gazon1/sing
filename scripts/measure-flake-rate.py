#!/usr/bin/env python3
"""measure-flake-rate.py — measure the per-test flake rate over N repeated runs.

## The gap this fills

`check-flaky-tests.py` compares **two** runs (the previous successful one and
this one) and reports a status flip as a flake witness. That is the right
signal for a single CI run against its predecessor, but it is one sample per
run and it is a boolean per test: either this run flipped or it did not. A test
that fails 1 run in 4 looks identical to a test that fails 1 run in 1000 —
both are "one observed flip" — and neither number is knowable from two runs.

The ADR `2026-10-04-measurement-integrity` states the generalisation this
operationalises: *any gate that reports a boolean where it could report a
magnitude should report the magnitude*. Flakiness is a magnitude (a rate over
repeated trials), not a boolean (did this particular pair of runs differ), so
this script measures the rate directly.

## Two distinct signals, both measured here

A "flake" is two different things, and conflating them hides the one you did
not instrument:

* **Flake B — per-test pass rate.** The same test, run N times in a row in the
  same job, that passes on some attempts and fails on others. The attempts are
  already counted in the JUnit XML: Gradle's `reports.junitXml.mergeReruns=true`
  (desktopApp/build.gradle.kts) writes one `<testcase>` with `<flakyFailure>`
  children, one per attempt that failed before a later attempt passed. A
  `<testcase>` with N-1 `<flakyFailure>` children and no final `<failure>`
  therefore proves the test passed exactly once out of N attempts, without any
  cross-run bookkeeping. This is the primary signal: it needs only one Gradle
  invocation per sample and survives artifact retention, because the evidence
  lives inside a single run's XML.

* **Flake A — status flips between runs.** A test that fails in run k and
  passes in run k+1, judged across separately-uploaded run directories. This is
  the signal `check-flaky-tests.py` already computes; it is re-derived here
  (via `classify.run_flip_buckets`) so a single scheduled job can report both
  from the same N directories, and so the flip evidence — the valuable half —
  is visible next to the rate rather than only in a separate advisory step.

Both are reported. A test can be "always passes when it runs, but the class
fails to construct under load" (Flake A only), or "passes on a warm daemon and
fails on a cold one within the same job" (Flake B only). Reporting only one
kind would silently discard the other.

## What the numbers mean

* `attempts` = N (the `--runs` count), the number of times each test was
  expected to execute.
* `passed` / `failed` / `flaky_failures` are counted **per attempt**, summed
  across all `<testcase>` occurrences in every run's XML. A test that ran N
  times contributes exactly N to one of {passed, failed} plus, if it had
  retry-attempts, up to N-1 `flaky_failures`.
* `flaky` is any test where `flaky_failures > 0` (it failed on at least one
  attempt and later passed) **or** its status flipped between consecutive runs
  (Flake A). This is the count the floor below gates on.

## The floor is a magnitude, not a boolean

`--max-flaky-rate` is a **ceiling on the fraction of tests that flaked**, not a
pass/fail flag. A rate is the honest unit because a raw flaked-count grows
trivially as the suite grows, and a boolean ("any flake?") is satisfied by a
suite of one and by a suite of ten thousand alike — it cannot distinguish "1
test is flaky" from "1% of the suite is flaky". The default ceiling is 0.0:
a legitimate scheduled run has no flaky tests, so any flake is investigated,
not tolerated. Raising it is a deliberate act that must name why a rate above
the current one is the smallest legitimate floor (same discipline as
`config/docs/test-runs-baseline.txt`).

A `--baseline` file (class or class::case ids with a reason) acknowledges known
flaky tests the same way `config/docs/flaky-baseline.txt` does, and is read
through the same loader so the two cannot drift. An acknowledged flake is
reported but does not count toward the ceiling; an unacknowledged one does. This
is the one place a `continue-on-error`-style tolerance is allowed, and it is
bounded by the requirement that every entry names the nondeterminism — a silent
`|| true` is exactly what the measurement-integrity ADR forbids.

## Usage

    scripts/measure-flake-rate.py --current run-01 run-02 run-03 --runs 3
    scripts/measure-flake-rate.py --current ... --summary "$GITHUB_STEP_SUMMARY"
    scripts/measure-flake-rate.py --current ... --baseline config/docs/flaky-baseline.txt

Each `--current` argument is a directory containing JUnit XML for one run, in
the order the runs executed (so consecutive-run flips are computed in the right
order). `--runs` is the expected attempt count; a test that appears in fewer
than `--runs` directories is reported as `missing`, because a test that did not
execute is the thing a run floor exists to catch (ADR test-execution-integrity)
— it is never silently folded into "passed".

Fails (exit 1) when the unacknowledged flake rate exceeds `--max-flaky-rate`,
or when any test is `missing`. Never suppresses a real failure: the build
verdict still comes from Gradle, this only annotates it.
"""
from __future__ import annotations

import argparse
import pathlib
import sys

# Reuse the one ElementTree JUnit reader so a flaky `<flakyFailure>`-shaped
# `<testcase>` is classified from the same code path every other pipeline uses.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "src"))
from traceability.junit_xml import TestCaseResult, parse_junit  # noqa: E402

import importlib.util  # noqa: E402

_flaky_spec = importlib.util.spec_from_file_location(
    "check_flaky_tests",
    str(pathlib.Path(__file__).resolve().parent / "check-flaky-tests.py"),
)
_check_flaky = importlib.util.module_from_spec(_flaky_spec)
_flaky_spec.loader.exec_module(_check_flaky)  # type: ignore[union-attr]

#: Gradle writes one `<flakyFailure>` child per attempt that failed before a
#: later attempt passed, when `reports.junitXml.mergeReruns=true`. Their count
#: is therefore the number of *failed attempts* for an otherwise-passing test.
FLAKY_FAILURE_TAG = "flakyFailure"


def _flaky_failure_count(directory: pathlib.Path) -> dict[str, int]:
    """test id -> number of `<flakyFailure>` children across every run XML.

    Counted directly from the raw XML rather than from ``parse_junit``'s
    ``TestCaseResult``, because that reader intentionally reports only the
    terminal outcome (``passed``/``failed``/``error``/``skipped``) and has no
    field for a retry-attempt count. The two readers therefore share a file
    format and a root-shape normalisation but not a dataclass, and that is
    deliberate: a retry count is a flake-rate concern, not a traceability one.
    """
    import xml.etree.ElementTree as ET

    counts: dict[str, int] = {}
    if not directory.is_dir():
        return counts
    for xml_file in sorted(directory.glob("**/*.xml")):
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for case in suite.findall("testcase"):
                ident = f"{case.get('classname', '?')}::{case.get('name', '?')}"
                n = len(case.findall(FLAKY_FAILURE_TAG))
                if n:
                    counts[ident] = counts.get(ident, 0) + n
    return counts


def _per_run_status(directory: pathlib.Path) -> dict[str, str]:
    """test id -> terminal status for one run's JUnit XML directory."""
    results = parse_junit([directory])
    return {f"{r.classname}::{r.name}": r.status for r in results}


def measure(runs: list[pathlib.Path], attempts: int) -> dict:
    """Aggregate per-test outcomes across ``attempts`` run directories.

    Returns a dict with, per test id: ``attempts`` (how many runs it appeared
    in), ``passed``/``failed`` counts summed across runs' terminal statuses,
    ``flaky_failures`` (total `<flakyFailure>` children across runs), and a
    boolean ``flaky`` (any retry-failure or a consecutive-run status flip).
    """
    statuses = [_per_run_status(d) for d in runs]
    flaky_counts = [_flaky_failure_count(d) for d in runs]

    all_ids: set[str] = set()
    for s in statuses:
        all_ids |= set(s)

    report: dict[str, dict] = {}
    for ident in sorted(all_ids):
        appeared = sum(1 for s in statuses if ident in s)
        passed = sum(1 for s in statuses if s.get(ident) == "passed")
        failed = sum(1 for s in statuses if s.get(ident) in ("failed", "error"))
        skipped = sum(1 for s in statuses if s.get(ident) == "skipped")
        flaky_failures = sum(fc.get(ident, 0) for fc in flaky_counts)
        # Flake A: the terminal status flipped between consecutive runs. A
        # transition to/from `skipped` is not a flake — a test that is
        # conditionally skipped and later runs is a coverage change, not a
        # timing failure, and folding it in would misreport the rate.
        flip = False
        for prev, curr in zip(statuses, statuses[1:]):
            a, b = prev.get(ident), curr.get(ident)
            if a is None or b is None or a == b:
                continue
            if "skipped" in (a, b):
                continue
            if a in ("failed", "error") or b in ("failed", "error"):
                flip = True
                break
        report[ident] = {
            "attempts": appeared,
            "passed": passed,
            "failed": failed,
            "skipped": skipped,
            "flaky_failures": flaky_failures,
            "flaky": flaky_failures > 0 or flip,
            "flip": flip,
        }
    return {
        "attempts_expected": attempts,
        "tests": report,
    }


def _load_acknowledged(path: pathlib.Path) -> dict[str, str]:
    """Acknowledge flaky ids the same way check-flaky-tests.py does.

    Delegated to ``load_baseline`` so the two files (``config/docs/
    flaky-baseline.txt`` and any ad-hoc ``--baseline``) cannot drift in what a
    bare class name or a reason comment means.
    """
    return _check_flaky.load_baseline(path)


def _is_acknowledged(ident: str, acknowledged: dict[str, str]) -> bool:
    if ident in acknowledged:
        return True
    return ident.split("::", 1)[0] in acknowledged


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--current",
        nargs="+",
        required=True,
        metavar="DIR",
        help="JUnit XML dir of each run, in execution order (one per attempt)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=0,
        help="expected attempt count per test; default = number of --current dirs",
    )
    parser.add_argument(
        "--baseline",
        default="",
        help="acknowledged flaky ids file (default config/docs/flaky-baseline.txt)",
    )
    parser.add_argument(
        "--max-flaky-rate",
        type=float,
        default=0.0,
        help="ceiling on (unacknowledged flaky tests) / (total tests); default 0.0",
    )
    parser.add_argument("--summary", default="", help="append a markdown section here")
    args = parser.parse_args()

    runs = [pathlib.Path(d) for d in args.current]
    attempts = args.runs or len(runs)
    if attempts < 1:
        print("--runs must be >= 1", file=sys.stderr)
        return 1

    result = measure(runs, attempts)
    tests = result["tests"]
    if not tests:
        print("no JUnit XML found in any run directory — run the tests first", file=sys.stderr)
        return 1

    baseline_default = (
        pathlib.Path(__file__).resolve().parent.parent
        / "config"
        / "docs"
        / "flaky-baseline.txt"
    )
    baseline_path = pathlib.Path(args.baseline) if args.baseline else baseline_default
    acknowledged = _load_acknowledged(baseline_path) if baseline_path.exists() else {}

    flaky_tests = {i: t for i, t in tests.items() if t["flaky"]}
    unacknowledged = {
        i: t for i, t in flaky_tests.items() if not _is_acknowledged(i, acknowledged)
    }
    known = {i: t for i, t in flaky_tests.items() if _is_acknowledged(i, acknowledged)}
    missing = {i: t for i, t in tests.items() if t["attempts"] < attempts}

    total = len(tests)
    rate = len(unacknowledged) / total if total else 0.0

    lines = [
        f"- **runs**: {attempts} (attempt count per test)",
        f"- **tests observed**: {total}",
        f"- **flaky tests** (retry-failure or status flip): {len(flaky_tests)}",
        f"  - acknowledged: {len(known)}",
        f"  - unacknowledged: {len(unacknowledged)}",
        f"- **unacknowledged flake rate**: {rate:.4f} (ceiling {args.max_flaky_rate})",
    ]
    if unacknowledged:
        lines.append("- **unacknowledged flaky**:")
        lines.extend(
            f"  - `{i}` — {t['passed']}p/{t['failed']}f, "
            f"{t['flaky_failures']} retry-failures, flip={t['flip']}"
            for i, t in sorted(unacknowledged.items())
        )
    if known:
        lines.append("- **acknowledged flaky**:")
        lines.extend(
            f"  - `{i}` — {t['passed']}p/{t['failed']}f, flip={t['flip']}"
            for i, t in sorted(known.items())
        )
    if missing:
        lines.append(f"- **missing** (ran in <{attempts} runs): {len(missing)}")
        lines.extend(f"  - `{i}` ({t['attempts']}/{attempts})" for i, t in sorted(missing.items()))

    print(f"measured {total} tests across {attempts} runs")
    print(f"flaky: {len(flaky_tests)} ({len(unacknowledged)} unacknowledged, rate {rate:.4f})")

    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as fh:
            fh.write("\n## Flake rate (per-test over repeated runs)\n\n")
            fh.write("\n".join(lines) + "\n")

    if missing:
        print(
            f"\n{len(missing)} test(s) ran in fewer than {attempts} runs — a test that "
            "did not execute cannot be measured as non-flaky.",
            file=sys.stderr,
        )
        return 1

    if rate > args.max_flaky_rate:
        print(
            f"\nUnacknowledged flake rate {rate:.4f} exceeds ceiling "
            f"{args.max_flaky_rate}.\n"
            "If the nondeterminism is understood, acknowledge it in "
            f"{baseline_path} with the reason. If it is not, this is a real "
            "failure and the fix belongs in the code, not the file.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
