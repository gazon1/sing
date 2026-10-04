#!/usr/bin/env python3
"""check-coverage.py — fail when instruction coverage falls below the recorded floor.

## Why a floor and not a percentage target

A coverage *target* ("reach 80%") fails on the day it is written, because the
number nobody can reach is not a gate anyone keeps. A floor does one job: it
fails when coverage *drops*. Growth is unconstrained and needs no decision.

The rule for the number itself is the same as for `test-runs-baseline.txt`:
record the **smallest** value any legitimate configuration produces, never a
round aspirational figure.

## What it measures, and what it deliberately does not

Coverage is computed over Kover's **own** packages only (`com.singularity.todo`),
not over everything the IntelliJ coverage runtime touched. A total over the whole
report is dominated by third-party bytecode that Kover never measures but still
lists, and its value drifts with unrelated dependency bumps — a floor built on it
would report regressions nobody caused and miss real ones.

It also says nothing about *which* test task was instrumented. Before
2026-10-04 `jvmTest` was excluded from instrumentation (ADR
`2026-09-25-test-jvm-heap-default`), so the published number described the
Android host source set alone: 10.5% instruction coverage, a measurement of
Gradle configuration rather than of the code. The instrumentation is now
restricted to our own packages instead of switched off wholesale, so the number
reflects the main test task too. That fix changed the value — which is precisely
why the floor, not the old figure, is the baseline.

## Baseline

`config/docs/coverage-baseline.txt`, one floor per metric:

    <metric> <percent with one decimal>

## Usage

    scripts/check-coverage.py                     # compare against the floor
    scripts/check-coverage.py --if-present        # skip cleanly when no report exists
    scripts/check-coverage.py --update-baseline   # after adding tests
    scripts/check-coverage.py --report path/to/report.xml

A DROP is a regression to investigate. A RISE needs no edit: raise the floor in
the same commit that caused it, so the floor keeps biting.

## Freshness

A coverage report is a build artifact, and a stale one is worse than none: the
floor is satisfied by a report from a run that no longer corresponds to the code.
`--since <epoch-seconds>` fails a report older than the given instant, for the same
reason `check-test-runs.py` takes it — a number that describes yesterday's code is
not a measurement of today's.

## The report path

Kover 0.9 writes `shared/build/reports/kover/report.xml`. The CI job uploaded
`xml-report.xml` — a name this plugin has never produced — with no
`if-no-files-found: error`, so the upload silently carried nothing and the
coverage artifact had never once been published. Both facts are the same shape
of defect this repo keeps fixing: a step that succeeds without doing its job.
"""
import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "config" / "docs" / "coverage-baseline.txt"
DEFAULT_REPORT = ROOT / "shared" / "build" / "reports" / "kover" / "report.xml"

#: Kover lists every class it saw, including uninstrumented third-party bytecode.
#: Counting those would make the floor a measure of the dependency graph.
OWN_PACKAGE_PREFIX = "com/singularity/todo"

#: counter type -> human label. Branch coverage is the strictest signal here;
#: a change that adds `if` without a test shows up there long before in totals.
METRICS = ("INSTRUCTION", "BRANCH", "LINE")

BASELINE_HEADER = [
    "# Instruction/branch/line coverage floors for com.singularity.todo,",
    "# as a percentage with one decimal. Read by scripts/check-coverage.py.",
    "#",
    "# Record the SMALLEST value a legitimate run produces, and raise it in the same",
    "# commit that produces a higher one. A floor that only ever gets regenerated",
    "# downward is not a gate.",
    "#",
    "# Regenerate with: python3 scripts/check-coverage.py --update-baseline",
]


def measure(report: pathlib.Path, package_prefix: str = OWN_PACKAGE_PREFIX, since: float | None = None):
    """metric -> (covered, missed) summed over our own packages, or None if absent.

    Returns None when the report is missing, unreadable, contains none of our
    packages, or — with *since* — predates that instant.
    """
    if not report.is_file():
        return None
    if since is not None and report.stat().st_mtime < since:
        return None
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError:
        return None
    totals = {m: [0, 0] for m in METRICS}
    seen = False
    for package in root.iter("package"):
        name = package.get("name", "")
        if not name.startswith(package_prefix):
            continue
        seen = True
        for counter in package.findall("counter"):
            kind = counter.get("type")
            if kind in totals:
                totals[kind][0] += int(counter.get("covered", 0))
                totals[kind][1] += int(counter.get("missed", 0))
    if not seen:
        return None
    return {m: (c, ms) for m, (c, ms) in totals.items()}


def percent(covered: int, missed: int) -> float:
    total = covered + missed
    return 0.0 if total == 0 else round(100.0 * covered / total, 1)


def load_baseline(path: pathlib.Path):
    entries = {}
    if not path.exists():
        return entries
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            entries[parts[0].upper()] = float(parts[1])
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default=str(DEFAULT_REPORT), help="Kover XML report")
    parser.add_argument("--baseline", default=str(BASELINE))
    parser.add_argument("--if-present", action="store_true",
                        help="exit 0 when the report does not exist")
    parser.add_argument(
        "--since",
        type=float,
        default=None,
        metavar="EPOCH",
        help="fail when the report predates this epoch-seconds stamp",
    )
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    report = pathlib.Path(args.report)
    baseline_path = pathlib.Path(args.baseline)

    observed = measure(report, since=args.since)
    if observed is None:
        stale = report.is_file() and args.since is not None and report.stat().st_mtime < args.since
        message = (
            f"the Kover report at {report} is older than --since — it describes an "
            f"earlier run, not this one"
            if stale else
            f"no Kover report at {report} — run :shared:koverXmlReport"
        )
        if args.if_present:
            print(f"{message} (skipped)")
            return 0
        print(message, file=sys.stderr)
        return 1

    current = {m: percent(*observed[m]) for m in METRICS}

    if args.update_baseline:
        lines = list(BASELINE_HEADER)
        for metric in METRICS:
            lines.append(f"{metric} {current[metric]}")
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"baseline written: {baseline_path}")
        for metric in METRICS:
            covered, missed = observed[metric]
            print(f"  {metric}: {current[metric]}% ({covered} covered / {missed} missed)")
        return 0

    baseline = load_baseline(baseline_path)
    if not baseline:
        print(f"no baseline at {baseline_path} — run --update-baseline", file=sys.stderr)
        return 1

    regressions = []
    for metric in METRICS:
        floor = baseline.get(metric)
        if floor is None:
            continue
        if current[metric] < floor:
            regressions.append(
                f"{metric}: {current[metric]}%, floor {floor}% "
                f"({current[metric] - floor:+.1f})"
            )

    if not args.quiet:
        for metric in METRICS:
            covered, missed = observed[metric]
            floor = baseline.get(metric, 0.0)
            print(f"{metric}: {current[metric]}% (floor {floor}%) "
                  f"[{covered} covered / {missed} missed]")

    if regressions:
        print("\nCoverage below the recorded floor:", file=sys.stderr)
        for line in regressions:
            print(f"  {line}", file=sys.stderr)
        print(
            "\nEither real coverage was lost, or a test source set stopped being\n"
            "measured (check :shared:koverXmlReport and check-test-runs.py first —\n"
            "a task that no longer runs looks exactly like a coverage drop).",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print("Coverage floors met.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
