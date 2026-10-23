#!/usr/bin/env python3
"""Parse Kover XML reports and emit a markdown coverage table.

Emits a markdown table to stdout with per-package LINE / BRANCH / INSTRUCTION
coverage. Designed to run in CI after `koverReport` has produced the XML
artifact (build/reports/kover/report.xml).

With `--summary`, a second block is printed first: the report's ROOT
counters — the class count and the three instruction/branch/line totals.

Why the class count is the one to read
--------------------------------------
The percentages alone cannot tell a full report from a partial one. The fast
population's report held 4735 classes over 579,002 instructions on 2026-10-23,
while the full population's report of 2026-10-22 held 4642 classes over 565,144:
the fast report is not a fraction of the universe, it is the same universe on a
tree that grew. A reader who compares "28.7%" with the recorded floor and sees a
number that looks like a fifth of the codebase has misread the report — and then
spends an hour measuring something that was never broken.

The class count is the number that distinguishes the two, so it is printed.
It is also the number that says which POPULATION the report measured: the
fast population covers fewer classes than the full one, and the coverage
floors are recorded per population for exactly that reason.

The denominator is the report's ROOT counters. A number read out of the XML by
summing every counter of a type counts each instruction once per level it is
nested in — method, class, source file, package, root — and so reads five
times the report's own denominator, while the percentages (a ratio) stay
correct. That is how a wrong denominator survives a real measurement.

Usage:
    python3 scripts/kover-markdown-table.py [xml_path]
    python3 scripts/kover-markdown-table.py --summary [xml_path]

Exit codes:
    0  — table printed (even if empty)
    1  — file not found or parse error (table not printed)
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

KOVER_NS = ""          # Kover XML has no namespace

#: The counters the summary prints, in the order a reader wants them.
SUMMARY_COUNTERS = (
    ("CLASS", "Classes"),
    ("INSTRUCTION", "Instructions"),
    ("BRANCH", "Branches"),
    ("LINE", "Lines"),
)


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


def format_summary(counters: dict[str, tuple[int, int, str]]) -> str:
    """The root counters as a small table.

    `counters` maps a counter type to `(covered, total, percentage)`.
    """
    if not counters:
        return "_No summary counters found._"

    lines = [
        "| What | Covered/total | % |",
        "|---|---|---|",
    ]
    for counter_type, label in SUMMARY_COUNTERS:
        entry = counters.get(counter_type)
        if entry is None:
            continue
        covered, total, pct = entry
        lines.append(f"| {label} | {covered}/{total} | {pct} |")
    return "\n".join(lines)


def parse_summary(xml_path: str | Path) -> dict[str, tuple[int, int, str]]:
    """The report root's own counters: `(covered, total, percentage)`.

    Kover writes one `<counter>` per package, per class and per method, and
    a set of totals at the ROOT of the report. Only the root's own counters
    are read here — a sum of the per-package counters would count the same
    class twice, once through its package and once through itself.
    """
    root = ET.parse(xml_path).getroot()
    counters: dict[str, tuple[int, int, str]] = {}
    for element in root.findall("counter"):
        counter_type = element.get("type")
        if counter_type is None:
            continue
        missed = int(element.get("missed", 0))
        covered = int(element.get("covered", 0))
        total = missed + covered
        counters[counter_type] = (covered, total, _pct(covered, total))
    return counters


def main() -> int:
    argv = [arg for arg in sys.argv[1:] if arg != "--summary"]
    want_summary = "--summary" in sys.argv[1:]
    xml_path = Path(argv[0]) if argv else Path("build/reports/kover/report.xml")

    if not xml_path.is_file():
        print(f"kover-markdown-table: {xml_path} not found", file=sys.stderr)
        return 1

    try:
        rows = parse_report(xml_path)
    except Exception as ex:
        print(f"kover-markdown-table: parse error: {ex}", file=sys.stderr)
        return 1

    if want_summary:
        try:
            counters = parse_summary(xml_path)
        except Exception as ex:
            print(f"kover-markdown-table: parse error: {ex}", file=sys.stderr)
            return 1
        print(format_summary(counters))
        print()

    print(format_table(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
