#!/usr/bin/env python3
"""Coverage ratchet for a package scope in a kover XML report.

Fails when measured line coverage drops below the committed baseline. The point
is not to reach a number — it is to make a *drop* loud. Every increase has to be
adopted deliberately with `--update`, which is also when the reviewer sees what
caused it.

## Why this exists instead of a kover verify bound

`:shared:koverXmlReport` can only aggregate test tasks whose classes kover
instruments, and instrumentation for `:shared:jvmTest` is off by default (see
the `kover` block in `shared/build.gradle.kts` and
`docs/decisions/2026-09-27-write-layer-soundness.md` ledger #11). With it off,
the report is generated but every counter under the agenda package is 0 — a
bound pinned to that number would ratchet on nothing at all. So the measurement
run passes `-Pkover.jvmTest=true -Pcoverage.tests=…` to instrument a filtered
run, and this script reads the result.

## Reproducibility

Kover merges binary reports incrementally, so a report produced after other
runs can carry coverage from tests outside the current filter. The baseline is
only meaningful against a clean state, which is why the just recipe removes
`shared/build/kover` before measuring. Do not point this script at a report
produced some other way.

## Scope

The default scope excludes the Compose-only subtrees
(`presentation/screen`, `presentation/nav`, `presentation/components`): they are
exercised by the desktopApp flow tests, which kover cannot see, so including
them would report mostly "this harness cannot run UI tests" rather than anything
about test quality. The whole-package number is recorded in the baseline file for
context. See the ADR for the full reasoning.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_REPORT = "shared/build/reports/kover/report.xml"
DEFAULT_BASELINE = "config/coverage-ratchet.json"
DEFAULT_SCOPE = "com/singularity/todo/feature/agenda"
DEFAULT_EXCLUDE = (
    "com/singularity/todo/feature/agenda/presentation/screen",
    "com/singularity/todo/feature/agenda/presentation/nav",
    "com/singularity/todo/feature/agenda/presentation/components",
)


def line_coverage(report_path: str, scope: str, exclude: tuple[str, ...]) -> tuple[int, int]:
    """Summed LINE covered/missed over the packages in scope."""
    if not os.path.isfile(report_path):
        sys.exit(
            f"coverage-ratchet: no kover report at {report_path}\n"
            "Generate one with:\n"
            "  just coverage-ratchet --measure   (or see the recipe for the raw gradle command)"
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
    if covered + missed == 0:
        sys.exit(
            f"coverage-ratchet: the report has no line data under {scope}.\n"
            "That means nothing was instrumented for this scope — check that the\n"
            "measurement run passed -Pkover.jvmTest=true and that kover's binary\n"
            "reports were not stale (the just recipe clears them first)."
        )
    return covered, missed


def whole_package(report_path: str, scope: str) -> tuple[int, int]:
    return line_coverage(report_path, scope, ())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default=DEFAULT_REPORT)
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    parser.add_argument("--scope", default=DEFAULT_SCOPE)
    parser.add_argument("--update", action="store_true", help="adopt the measured value as the new floor")
    parser.add_argument(
        "--measure",
        action="store_true",
        help="print the measured value without comparing (used by the just recipe)",
    )
    args = parser.parse_args()

    covered, missed = line_coverage(args.report, args.scope, DEFAULT_EXCLUDE)
    total = covered + missed
    percent = 100.0 * covered / total
    all_covered, all_missed = whole_package(args.report, args.scope)
    all_percent = 100.0 * all_covered / (all_covered + all_missed)

    print(f"scope      {args.scope} (excluding presentation/screen, nav, components)")
    print(f"measured   {covered}/{total} lines = {percent:.2f}%")
    print(f"context    whole package {all_covered}/{all_covered + all_missed} = {all_percent:.2f}%")

    if args.measure:
        return 0

    with open(args.baseline, encoding="utf-8") as handle:
        baseline = json.load(handle)

    floor = float(baseline["min_line_percent"])

    if args.update:
        baseline.update(
            {
                "min_line_percent": round(percent, 2),
                "measured_covered_lines": covered,
                "measured_total_lines": total,
                "whole_package_percent": round(all_percent, 2),
            }
        )
        with open(args.baseline, "w", encoding="utf-8") as handle:
            json.dump(baseline, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(f"baseline   adopted {percent:.2f}% (was {floor:.2f}%) in {args.baseline}")
        return 0

    print(f"baseline   {floor:.2f}%")
    # Compare at the stored precision: the baseline is rounded to 2 decimals, so
    # an un-rounded comparison would report a phantom rise on every run.
    if round(percent, 2) + 1e-9 < floor:
        print(
            f"\nCOVERAGE DROPPED: {percent:.2f}% < {floor:.2f}%\n"
            "Either a test stopped running (check the --tests filter and the kover state)\n"
            "or production code grew without tests. If the drop is understood and\n"
            "accepted, re-run with --update and say why in the commit message."
        )
        return 1
    if round(percent, 2) > floor:
        print(f"\ncoverage rose by {round(percent, 2) - floor:.2f} points — adopt it: --update")
    return 0


if __name__ == "__main__":
    sys.exit(main())
