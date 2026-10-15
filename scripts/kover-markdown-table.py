#!/usr/bin/env python3
"""Parse Kover XML reports and emit a markdown coverage table.

Emits a markdown table to stdout with per-module LINE / BRANCH / INSTRUCTION
coverage. Designed to run in CI after `koverReport` has produced the XML
artifact (build/reports/kover/report.xml).

Usage:
    python3 scripts/kover-markdown-table.py [xml_path]

Exit codes:
    0  — table printed (even if empty)
    1  — file not found or parse error (table not printed)
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

KOVER_NS = ""          # Kover XML has no namespace


def last_counter(root: ET.Element, counter_type: str) -> tuple[int, int]:
    """Return (missed, covered) for the last counter of the given type.

    Kover emits one <counter> per package/class AND one at the module/report
    root level.  The root-level total is always the *last* occurrence, so we
    scan the whole tree and keep overwriting.
    """
    missed = covered = 0
    for el in root.iter("counter"):
        if el.get("type") == counter_type:
            missed  = int(el.get("missed",  0))
            covered = int(el.get("covered", 0))
    return missed, covered


def parse_report(xml_path: str | Path) -> list[dict]:
    """Return a list of {module, line, branch, instruction} dicts."""
    tree = ET.parse(xml_path)
    root = tree.getroot()
    results = []

    for project in root.findall("project") + root.findall("package"):   # fallback: some formats nest under <package>
        name = project.get("name", "?")
        missed_l, cov_l   = last_counter(project, "LINE")
        missed_b, cov_b   = last_counter(project, "BRANCH")
        missed_i, cov_i   = last_counter(project, "INSTRUCTION")

        total_l  = missed_l + cov_l
        total_b  = missed_b + cov_b
        total_i  = missed_i + cov_i

        pct_l = _pct(cov_l, total_l)
        pct_b = _pct(cov_b, total_b)
        pct_i = _pct(cov_i, total_i)

        results.append({
            "module":   name,
            "line":     (cov_l, total_l, pct_l),
            "branch":   (cov_b, total_b, pct_b),
            "instruction": (cov_i, total_i, pct_i),
        })

    return results


def _pct(covered: int, total: int) -> str:
    if total == 0:
        return "n/a"
    return f"{covered / total * 100:.1f}%"


def format_table(rows: list[dict]) -> str:
    if not rows:
        return "_No coverage data found._"

    header = "| Module | LINE (covered/total) | BRANCH (covered/total) | INSTRUCTION (covered/total) |"
    sep    = "|---|---|---|---|"

    lines = [header, sep]
    for r in rows:
        def cell(key: str) -> str:
            cov, tot, pct = r[key]
            if tot == 0:
                return "—"
            return f"{cov}/{tot} ({pct})"

        lines.append(f"| {r['module']} | {cell('line')} | {cell('branch')} | {cell('instruction')} |")

    return "\n".join(lines)


def main() -> int:
    xml_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/reports/kover/report.xml")

    if not xml_path.is_file():
        print(f"kover-markdown-table: {xml_path} not found", file=sys.stderr)
        return 1

    try:
        rows = parse_report(xml_path)
    except Exception as ex:
        print(f"kover-markdown-table: parse error: {ex}", file=sys.stderr)
        return 1

    print(format_table(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
