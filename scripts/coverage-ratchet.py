#!/usr/bin/env python3
"""Coverage ratchet: fail when line coverage drops below a committed floor.

The point is not to reach a number — it is to make a *drop* loud. Every increase
has to be adopted deliberately with `--update`, which is also when a reviewer
sees what caused it.

## Why this exists instead of a kover verify bound

`:shared:koverXmlReport` can only aggregate test tasks whose classes kover
instruments, and instrumentation for `:shared:jvmTest` is **off by default** —
with it off, the report is generated but every counter under the agenda package
is 0, and a bound pinned to that ratchets on nothing. The measurement run
therefore passes `-Pkover.jvmTest=true`.

That flag is a *speed* choice, not a safety one. The OOM that originally
justified disabling instrumentation was misattributed: it reproduces with kover
off and in complete isolation (`2026-09-27-write-layer-soundness.md` ledger
#11). A full instrumented `:shared:jvmTest` was measured on 2026-10-04 — it
completes in 9m27s without OOM. Keeping it off by default only means
`./check.sh` does not pay those six minutes on every run.

## Reproducibility

Kover merges binary reports incrementally, so a report produced after other runs
can carry coverage from tests outside the current filter. The floors are only
meaningful against a clean state, which is why the just recipe removes
`shared/build/kover` before measuring. Do not point this script at a report
produced some other way.

## Multiple floors

`config/coverage-ratchet.json` holds a list of `floors`, each with its own
scope. A single-feature floor cannot see erosion elsewhere — with only the agenda
floor, deleting every test in `feature/tasks` would leave the gate green — so
there is a whole-module floor as well.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_REPORT = "shared/build/reports/kover/report.xml"
DEFAULT_BASELINE = "config/coverage-ratchet.json"
# Compose-only subtrees of the agenda feature: exercised by the desktopApp flow
# tests, which kover cannot see. Excluded from the agenda floor so it measures
# logic coverage rather than "this harness cannot run UI tests".
DEFAULT_EXCLUDE = (
    "com/singularity/todo/feature/agenda/presentation/screen",
    "com/singularity/todo/feature/agenda/presentation/nav",
    "com/singularity/todo/feature/agenda/presentation/components",
)


def line_coverage(
    report_path: str,
    scope: str,
    exclude: tuple[str, ...] = (),
) -> tuple[int, int]:
    """Summed LINE covered/missed over the packages in scope."""
    if not os.path.isfile(report_path):
        sys.exit(
            f"coverage-ratchet: no kover report at {report_path}\n"
            "Generate one with `just cr` (see the recipe for the raw gradle command)."
        )
    covered = missed = 0
    root = ET.parse(report_path).getroot()
    for package in root.iter("package"):
        name = package.get("name", "")
        if not name.startswith(scope) or name in exclude:
            continue
        for klass in package.findall("class"):
            for counter in klass.findall("counter"):
                if counter.get("type") == "LINE":
                    covered += int(counter.get("covered", 0))
                    missed += int(counter.get("missed", 0))
    return covered, missed


def percent_of(floor: dict, exclude: tuple[str, ...] = ()) -> tuple[int, int, float]:
    """Measured covered/total/percent for one floor under the given exclusion."""
    covered, missed = line_coverage(DEFAULT_REPORT, floor["scope"], exclude)
    total = covered + missed
    if total == 0:
        sys.exit(
            f"coverage-ratchet: no line data under {floor['scope']}.\n"
            "Nothing was instrumented for this scope — check that the measurement run\n"
            "passed -Pkover.jvmTest=true and that kover's binary reports were not stale\n"
            "(the just recipe clears them first)."
        )
    return covered, total, 100.0 * covered / total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default=DEFAULT_REPORT)
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    parser.add_argument("--update", action="store_true", help="adopt the measured values as the new floors")
    parser.add_argument("--measure", action="store_true", help="print measurements without comparing")
    args = parser.parse_args()

    with open(args.baseline, encoding="utf-8") as handle:
        baseline = json.load(handle)

    floors = baseline["floors"]
    measured: list[tuple[dict, int, int, float]] = []
    for floor in floors:
        # The Compose-only exclusion applies to the agenda floor only, and is
        # decided by the baseline naming the excluded subtrees — not by a
        # substring match on the scope, which would silently mis-measure.
        excluded = (
            DEFAULT_EXCLUDE
            if floor.get("exclude_compose_subtrees")
            else ()
        )
        covered, total, percent = percent_of(floor, excluded)
        measured.append((floor, covered, total, percent))
        print(f"{floor['label']}")
        print(f"  measured {covered}/{total} lines = {percent:.2f}%")
        if excluded:
            _, whole_total, whole_percent = percent_of(floor)  # no exclusion
            print(f"  (whole scope {whole_total} lines = {whole_percent:.2f}%)")

    if args.measure:
        return 0

    failures: list[str] = []
    for floor, covered, total, percent in measured:
        want = float(floor["min_line_percent"])
        # Compare at the stored precision: the floor is rounded to 2 decimals, so
        # an un-rounded comparison reports a phantom rise on every run.
        if round(percent, 2) + 1e-9 < want:
            failures.append(
                f"  {floor['label']}: {percent:.2f}% < floor {want:.2f}%  "
                f"({covered}/{total} lines)"
            )
        elif round(percent, 2) > want and not args.update:
            print(
                f"  {floor['label']}: rose to {percent:.2f}% (floor {want:.2f}%) — adopt with --update"
            )

    if args.update:
        for floor, covered, total, percent in measured:
            floor["min_line_percent"] = round(percent, 2)
            floor["measured_covered_lines"] = covered
            floor["measured_total_lines"] = total
        with open(args.baseline, "w", encoding="utf-8") as handle:
            json.dump(baseline, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(f"\nbaseline adopted in {args.baseline}")
        return 0

    if failures:
        print("\nCOVERAGE DROPPED:\n" + "\n".join(failures))
        print(
            "\nEither a test stopped running (check the measurement command) or production\n"
            "code grew without tests. If the drop is understood and accepted, re-run with\n"
            "--update and say why in the commit message."
        )
        return 1

    print("\nAll floors held.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
