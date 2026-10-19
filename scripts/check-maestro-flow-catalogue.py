#!/usr/bin/env python3
"""check-maestro-flow-catalogue.py — the flow catalogue must match the tree.

A Maestro suite is a directory, and a directory is easy to shrink without
anyone noticing. Delete a flow and the suite still runs, still reports green,
and simply covers less than it did: the run says "6 of 6 passed" about the six
that are left. The evidence that the sixth one was there to begin with is
nowhere but in a diff nobody reads.

So the flows this repository expects to have are written down, one per line,
in `config/docs/maestro-flows.txt`, and this gate holds the tree to that list
in both directions:

* a flow in the tree that the catalogue does not know about is a finding —
  it will run in CI, and nothing records that it exists;
* a flow in the catalogue that is not in the tree is a finding — the
  coverage it promised is gone.

Neither is a formatting complaint. Both mean "the suite is not the suite we
said it was", and a suite that silently changes shape makes every green
run before it incomparable to every green run after it.

Updating the catalogue is a deliberate act: add the flow to the file in the
same commit that adds the flow, and the gate is satisfied again.

Usage: python3 scripts/check-maestro-flow-catalogue.py [--json] [--check]
"""
from __future__ import annotations

import json
import pathlib
import sys

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
FLOWS_DIR = ROOT / "Maestro" / "flows"
CATALOGUE = ROOT / "config" / "docs" / "maestro-flows.txt"


def catalogue_entries() -> list[str]:
    """The flow paths the catalogue claims, in file order, comments removed."""
    try:
        text = CATALOGUE.read_text(encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(f"cannot read {CATALOGUE}: {exc}") from exc
    entries = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        entries.append(stripped)
    return entries


def tree_flows() -> list[str]:
    """The flow files that exist under Maestro/flows, as repo-relative paths.

    The repository root is derived from the flows directory rather than from
    this file's own location, so a test can point the gate at a tree of its
    own and get the same relative paths the real tree produces.
    """
    if not FLOWS_DIR.is_dir():
        return []
    root = FLOWS_DIR.parent.parent
    return sorted(
        str(p.relative_to(root))
        for p in FLOWS_DIR.rglob("*.yaml")
        if p.is_file()
    )


def scan() -> list[dict]:
    """The catalogue/tree mismatches, as a list of findings."""
    listed = catalogue_entries()
    present = tree_flows()
    listed_set = set(listed)
    present_set = set(present)

    findings: list[dict] = []
    for path in present:
        if path not in listed_set:
            findings.append(
                {
                    "flow": path,
                    "kind": "not in catalogue",
                    "detail": (
                        "the flow is in the tree, so it runs in CI, but the "
                        "catalogue does not record it"
                    ),
                }
            )
    for path in listed:
        if path not in present_set:
            findings.append(
                {
                    "flow": path,
                    "kind": "missing from tree",
                    "detail": (
                        "the catalogue promises this flow, and it is not in the "
                        "tree — the coverage it stood for is gone"
                    ),
                }
            )
    duplicates = sorted({p for p in listed if listed.count(p) > 1})
    for path in duplicates:
        findings.append(
            {
                "flow": path,
                "kind": "listed twice",
                "detail": "the catalogue lists the same flow more than once",
            }
        )
    return findings


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print the result without exiting non-zero")
@click.pass_context
def cli(ctx: click.Context, emit_json: bool, check: bool) -> None:
    try:
        findings = scan()
    except click.ClickException as exc:
        click.echo(f"maestro flow catalogue: {exc.format_message()}")
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(json.dumps({"findings": findings}, indent=2))
    elif not findings:
        click.echo(
            f"maestro flow catalogue: {len(tree_flows())} flow(s), all recorded"
        )
    else:
        click.echo(f"maestro flow catalogue: {len(findings)} mismatch(es)")
        for finding in findings:
            click.echo(f"  {finding['kind']}: {finding['flow']} — {finding['detail']}")
    if check:
        raise ctx.exit(CLEAN)
    raise ctx.exit(VALIDATION_FAILURE if findings else CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
