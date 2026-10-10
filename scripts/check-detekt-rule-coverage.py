#!/usr/bin/env python3
"""
Gate: fails when detekt-rules branch coverage falls below the floor.

The floor is enforced here rather than via koverVerify's Gradle DSL, because the
kover 0.9.x verify{} block API is not stable across minor versions and the
Gradle Kotlin DSL throws unresolved-reference errors for valid-looking syntax.

Branch coverage is extracted from the Kover XML report (written by
:detekt-rules:koverXmlReport) — the same data koverVerify would check, just parsed
in Python where the API is stable.

Floor: 60% — below the 2026-10-10 measurement of 65.1% (627/963 branches).

Usage:
  python3 scripts/check-detekt-rule-coverage.py
  python3 scripts/check-detekt-rule-coverage.py --report /path/to/report.xml
  python3 scripts/check-detekt-rule-coverage.py --floor 50.0
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_REPORT = ROOT / "detekt-rules" / "build" / "reports" / "kover" / "report.xml"
FLOOR = 60.0  # percent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--report",
        type=pathlib.Path,
        default=DEFAULT_REPORT,
        help="Path to Kover XML report",
    )
    parser.add_argument(
        "--floor",
        type=float,
        default=FLOOR,
        help="Minimum branch coverage %% (default: %(default)s)",
    )
    args = parser.parse_args()

    if not args.report.exists():
        print(
            f"FAIL: Kover report not found at {args.report}\n"
            f"Run: ./gradlew :detekt-rules:koverXmlReport",
            file=sys.stderr,
        )
        sys.exit(1)

    tree = ET.parse(args.report)
    root = tree.getroot()

    total_covered = 0
    total_missed = 0
    for counter in root.iter("counter"):
        if counter.get("type") == "BRANCH":
            total_covered += int(counter.get("covered", 0))
            total_missed += int(counter.get("missed", 0))

    total = total_covered + total_missed
    coverage = (total_covered / total * 100) if total > 0 else 0.0

    print(
        f"detekt-rules branch coverage: {total_covered}/{total} = {coverage:.1f}%\n"
        f"floor: {args.floor}%"
    )

    if coverage < args.floor:
        print(
            f"\nFAIL: branch coverage {coverage:.1f}% is below floor {args.floor}%\n"
            f"Fix: add tests for uncovered branches, then raise the floor."
        )
        sys.exit(1)

    print(f"\nPASS: branch coverage {coverage:.1f}% >= floor {args.floor}%")


if __name__ == "__main__":
    main()
