#!/usr/bin/env python3
"""detekt-summary.py — the detekt findings, in a form that fits in a screen.

The problem this solves
-----------------------
A detekt report is XML: one element per finding, with the rule, the file and
the line. A run with a hundred findings is a hundred elements, and reading it
means dumping hundreds of lines into a context that then has to be reasoned
about. On 2026-10-22 that happened twice, and the second time the answer was
in the first twenty lines of the output.

This prints the same facts in the order a reader wants them: the rules that
fired, how often each fired, and where. The finding text is dropped — the
message is in the report, and the report has a path.

It reads the reports detekt already wrote, so it costs nothing: run detekt
first, then this.

Usage:
    scripts/detekt-summary.py                       # every module that has a report
    scripts/detekt-summary.py --module shared       # one module
    scripts/detekt-summary.py --limit 5             # five places per rule
    scripts/detekt-summary.py --json                # JSON instead of text
    scripts/detekt-summary.py --report <path.xml>   # a report that is not in a module

Exit code is 0 when there is something to read and 1 when there is no report
at all — a report that is missing is a different thing from a report that is
empty.
"""
from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from pathlib import Path

import click

DEFAULT_ROOT = Path(__file__).resolve().parent.parent

#: Where detekt writes its reports, per module.
REPORTS = {
    "shared": "shared/build/reports/detekt/detekt.xml",
    "desktopApp": "desktopApp/build/reports/detekt/detekt.xml",
    "androidApp": "androidApp/build/reports/detekt/detekt.xml",
}


def read_report(path: Path) -> list[tuple[str, str, int]]:
    """The findings of one report — `(rule, file, line)`."""
    findings: list[tuple[str, str, int]] = []
    if not path.exists():
        return findings
    root = ET.parse(path).getroot()
    for file_element in root:
        name = file_element.get("name", "")
        for element in file_element:
            rule = element.get("source", "") or element.tag
            rule = rule.removeprefix("detekt.")
            findings.append((rule, name, int(element.get("line", "0"))))
    return findings


def summarise(findings: list[tuple[str, str, int]], limit: int) -> list[dict[str, object]]:
    """The findings grouped by rule, most frequent first."""
    grouped: dict[str, list[tuple[str, int]]] = {}
    for rule, name, line in findings:
        grouped.setdefault(rule, []).append((name, line))
    summary: list[dict[str, object]] = []
    for rule in sorted(grouped, key=lambda key: (-len(grouped[key]), key)):
        places = grouped[rule]
        summary.append(
            {
                "rule": rule,
                "count": len(places),
                "places": [
                    {"file": name, "line": line}
                    for name, line in sorted(places)[:limit]
                ],
            },
        )
    return summary


@click.command()
@click.option("--root", type=click.Path(file_okay=False, path_type=Path), default=DEFAULT_ROOT,
              help="The tree to read the reports from.")
@click.option("--module", "modules", multiple=True,
              help="Only this module's report. Repeatable.")
@click.option("--limit", default=10, show_default=True,
              help="How many places to show per rule.")
@click.option("--report", "reports", multiple=True, type=click.Path(path_type=Path),
              help="A report file to read instead of the known ones.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
def cli(root: Path, modules: tuple[str, ...], limit: int, reports: tuple[Path, ...], as_json: bool) -> None:
    """Print the detekt findings grouped by rule."""
    found: dict[str, list[tuple[str, str, int]]] = {}
    for report in reports:
        found[report.name] = read_report(report)
    for module, relative in REPORTS.items():
        if modules and module not in modules:
            continue
        path = root / relative
        findings = read_report(path)
        if findings or path.exists():
            found[module] = findings

    if not found:
        click.echo(
            "No detekt report found. Run a detekt task first — for example "
            "./gradlew :shared:detekt — and then this.",
        )
        raise SystemExit(1)

    payload = {
        module: {
            "total": len(findings),
            "rules": summarise(findings, limit),
        }
        for module, findings in found.items()
    }

    if as_json:
        click.echo(json.dumps(payload, indent=2))
        return

    for module, report in payload.items():
        total = report["total"]
        click.echo(f"{module}: {total} finding(s)")
        for entry in report["rules"]:
            places = ", ".join(f"{place['file'].rsplit('/', 1)[-1]}:{place['line']}" for place in entry["places"])
            click.echo(f"  {entry['rule']} — {entry['count']}")
            click.echo(f"      {places}")
    click.echo()
    click.echo("The full report: <module>/build/reports/detekt/detekt.md")


if __name__ == "__main__":
    cli()
