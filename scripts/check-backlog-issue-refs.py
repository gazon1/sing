#!/usr/bin/env python3
"""check-backlog-issue-refs.py — pure GitHub-issue backlog consistency gate.

Single invariant (I4) is enforced:

  I4  Every OPEN issue in the snapshot cites a backlog entry via a `Backlog:`
      field in its body. This is the reverse direction: the issue tracker is
      the queue, the backlog is the reasoning — both sides must agree.

The four original invariants (I1–I3) checked consistency between local deferred/
files and GH issues. PR #645 deleted that directory; those invariants are dead.
Only I4 remains relevant.

Output format follows K2: "current / expected / delta / reason" per finding.
Exit code 0 = all invariants pass; 1 = at least one invariant fails.

Usage:
    python3 scripts/check-backlog-issue-refs.py \\
        --snapshot config/docs/issues-snapshot.json
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import click
from sgate.commands.base import run

ROOT = Path(__file__).resolve().parent.parent

_BACKLOG_FIELD_RE = re.compile(r"(?i)^\s*Backlog:\s*([^\n]+)", re.M)

CLEAN = 0
VALIDATION_FAILURE = 1


def _load_snapshot(path: Path) -> dict[int, dict]:
    """Load the snapshot as {issue_number: {number, state, title, body}}."""
    if not path.is_file():
        click.echo(f"ERROR: snapshot not found: {path}", err=True)
        click.echo(
            "Run `just issues-refresh` to fetch the current issue state from GitHub.",
            err=True,
        )
        raise SystemExit(VALIDATION_FAILURE)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        click.echo(f"ERROR: snapshot is not valid JSON: {exc}", err=True)
        raise SystemExit(VALIDATION_FAILURE)
    result: dict[int, dict] = {}
    for item in data:
        if "number" not in item or "state" not in item:
            click.echo(
                f"ERROR: snapshot entry missing required field: {item!r}",
                err=True,
            )
            raise SystemExit(VALIDATION_FAILURE)
        result[int(item["number"])] = item
    return result


def _has_backlog_field(issue: dict) -> bool:
    """Return True if the issue body contains a non-empty Backlog: field."""
    body = issue.get("body") or ""
    m = _BACKLOG_FIELD_RE.search(body)
    return bool(m and m.group(1).strip())


def check_invariants(snapshot_path: Path) -> list[str]:
    """Return a list of finding lines; empty means all invariants pass."""
    snapshot = _load_snapshot(snapshot_path)
    findings: list[str] = []

    open_issues = [issue for issue in snapshot.values() if issue["state"] == "OPEN"]

    for issue in open_issues:
        if not _has_backlog_field(issue):
            findings.append(
                f"issue #{issue['number']}  [I4] open issue has no Backlog: field"
                f" — add `Backlog: <slug>` to the issue body"
            )

    return findings


@click.command()
@click.option(
    "--snapshot",
    type=click.Path(exists=False, path_type=Path),
    default=ROOT / "config" / "docs" / "issues-snapshot.json",
    help="issue snapshot file (default: issues-snapshot.json)",
)
@click.pass_context
def cli(ctx: click.Context, snapshot: Path) -> None:
    if not snapshot.is_absolute():
        snapshot = ROOT / snapshot

    findings = check_invariants(snapshot)

    snap = _load_snapshot(snapshot)
    open_issues = [i for i in snap.values() if i["state"] == "OPEN"]

    click.echo(f"backlog-issue-refs: {len(open_issues)} open backlog issues")

    if findings:
        click.echo("")
        for err in findings:
            click.echo(f"ERROR: {err}")

        click.echo("")
        click.echo(f"check-backlog-issue-refs: FAIL — {len(findings)} open issue(s) missing Backlog: field")
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo("  ok  all open backlog issues have a Backlog: field")
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
