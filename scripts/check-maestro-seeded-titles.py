#!/usr/bin/env python3
"""check-maestro-seeded-titles.py — a seeded fixture title carries no template variable.

## The defect this gate holds closed

The seed replaces the row its title names: `SeedWriter` looks for the title
among the live rows and in the trash, updates the row it finds, and restores it
if it was trashed; it finds none, it creates one. That is what makes a seeded
fixture the *same* row on the next run — `SeedWriterTest` proves it, and
`docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md` states why
the suite depends on it.

A title holding `${RUN_ID}` defeats it. The runner mints a fresh id on every
attempt, so `task=${RUN_ID}_PinnedTask2026` names a row that has never existed:
the seed creates a new task instead of updating the old one, and every run
leaves its own row behind. The failure is reported under the run id too —
`"r5869995730 PinnedTask2026" is visible` — which is a fixture whose name
nobody can read.

Seven flows carried the pattern and were renamed one at a time. Nothing would
have caught the eighth: the selector validator skips every id that holds a
template variable, so the row's own test tag cannot be checked either.

## What the gate reads

Each flow's seed call — the `QUERY` the seed helper is handed — and the four
keys that name a row the seed replaces: `task`, `note`, `project` and
`profile`. A template anywhere in one of those values is a finding.

The other keys are not titles. `due=`, `id=`, `session=`, `dependsOn=` and
`updatedAt=` are arguments to the seed, not names of rows it replaces, so a
template in one of those is not a finding.

The other direction is not a finding either: a title a flow *types* into the
editor reaches no seed, so the run id there is the flow's own business. This
gate reads `QUERY` lines and nothing else.

## What it cannot see

A flow that reached the seed activity without the helper — an `openLink` of
`todo-debug://seed?...` — is not a `QUERY` line. No flow does that today; a
gate that read it too would be a second parser for a second mechanism.

A `QUERY` handed to the helper through `runFlow … env:` from another variable
is still a `QUERY` line, and the template in it is still a finding.

Usage:
    python3 scripts/check-maestro-seeded-titles.py            # report
    python3 scripts/check-maestro-seeded-titles.py --quiet
    python3 scripts/check-maestro-seeded-titles.py --json
    python3 scripts/check-maestro-seeded-titles.py --flows DIR
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import click

# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOWS = ROOT / "Maestro" / "flows"

#: The seed helper's query keys whose value names a row the seed replaces.
TITLE_KEYS = ("task", "note", "project", "profile")

#: A Maestro template: `${RUN_ID}` today, and any other the suite grows.
TEMPLATE = re.compile(r"\$\{[^}]*\}")

#: The line that hands the query string to the seed helper.
QUERY_LINE = re.compile(r"^\s*QUERY:\s*(?P<value>.+?)\s*$")


def flow_files(flows: pathlib.Path) -> list[pathlib.Path]:
    """The flow files under `flows`, in file order."""
    if not flows.is_dir():
        return []
    return sorted(path for path in flows.rglob("*.yaml") if path.is_file())


def _label(path: pathlib.Path) -> str:
    """The flow's name in a finding — repository-relative when it is one."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return path.name


def query_value(raw: str) -> str:
    """The query string of a `QUERY:` line, its quotes and trailing comment gone."""
    value = raw.strip()
    if value[:1] in ("'", '"'):
        quote = value[0]
        end = value.find(quote, 1)
        if end != -1:
            return value[1:end]
    # Unquoted: a comment may follow the value.
    return value.split(" #", 1)[0].strip()


def seeded_titles(query: str) -> list[tuple[str, str]]:
    """The `(key, value)` pairs of a seed query that name a row to replace."""
    pairs: list[tuple[str, str]] = []
    for part in query.split("&"):
        key, separator, value = part.partition("=")
        if separator and key.strip() in TITLE_KEYS:
            pairs.append((key.strip(), value.strip()))
    return pairs


def scan(flows: pathlib.Path) -> list[dict]:
    """The seeded titles that carry a template variable, as findings."""
    findings: list[dict] = []
    for path in flow_files(flows):
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = QUERY_LINE.match(line)
            if not match:
                continue
            query = query_value(match.group("value"))
            for key, title in seeded_titles(query):
                if not TEMPLATE.search(title):
                    continue
                findings.append(
                    {
                        "flow": _label(path),
                        "line": index + 1,
                        "query": f"{key}={title}",
                        "detail": (
                            f"the seeded {key} title carries a template variable — "
                            "the seed replaces the row its title names, so this names "
                            "a row that has never existed and every run leaves its "
                            "own behind"
                        ),
                    }
                )
    return findings


@click.command()
@click.option(
    "--flows",
    "flows_dir",
    type=click.Path(path_type=pathlib.Path),
    default=FLOWS,
    help="The directory of Maestro flows to read (default: Maestro/flows).",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print the result without exiting non-zero")
@click.option("--quiet", is_flag=True, help="Say nothing when there is nothing to report")
@click.pass_context
def cli(
    ctx: click.Context,
    flows_dir: pathlib.Path,
    emit_json: bool,
    check: bool,
    quiet: bool,
) -> None:
    if not flows_dir.is_dir():
        click.echo(f"maestro seeded titles: no flows directory at {flows_dir} — nothing to check")
        raise ctx.exit(IO_ERROR)

    try:
        found = flow_files(flows_dir)
        findings = scan(flows_dir)
    except OSError as exc:
        click.echo(f"maestro seeded titles: cannot read the flows: {exc}")
        raise ctx.exit(IO_ERROR)

    if not found:
        # A rule that finds nothing to check has proved nothing, and a passing
        # gate that checked nothing is the failure this repository keeps paying
        # for.
        click.echo(f"maestro seeded titles: no flows under {flows_dir} — nothing to check")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    elif findings:
        click.echo(f"maestro seeded titles: {len(findings)} finding(s)")
        for finding in findings:
            click.echo(f"  {finding['flow']}:{finding['line']} — {finding['detail']}")
            click.echo(f"    seeded query: {finding['query']}")
    elif not quiet:
        click.echo(f"maestro seeded titles: OK ({len(found)} flows checked)")

    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
