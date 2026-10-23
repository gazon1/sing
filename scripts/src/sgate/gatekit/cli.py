"""sgate.gates.cli — the shape every gate command has.

The repository's own convention gate states the rules a gate command follows
(`scripts/check-script-conventions.py`, R2.1 and R2.2):

  * it is a Click command, not an argparse script;
  * it accepts `--check` and `--json`;
  * it exits 0 when it is clean and 1 when it has something to report.

Every gate used to spell those flags out again, and every gate spelled them
slightly differently: one took `--check` and ignored it, one printed its
report to stdout in a shape the JSON contract probe could not read, one
counted an advisory finding as a failure. The flags are the *interface* of a
gate — the thing the runner, the pre-push hook and the contract probe all
rely on — so they are written once, here.

The exit rule is the one that matters most: **only a blocking finding fails a
`--check` run.** An advisory finding is printed and does not fail the build.
"""
from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import click

from sgate.gatekit.findings import Finding, advisory_findings, blocking_findings

CLEAN = 0
VALIDATION_FAILURE = 1
CONFIG_ERROR = 3


def gate_command(
    report: Callable[[Path], list[Finding]],
    *,
    name: str,
    title: str,
    default_root: Path,
    note: str = "",
    root_help: str = "The tree to check. Defaults to the repository the script lives in.",
    write: Callable[[], int] | None = None,
) -> click.Command:
    """Build the Click command of a gate.

    `report` is the gate's whole job: it is given the tree to check and
    returns what it found. Everything else — the flags, the exit code, the
    text and the JSON shape — is the same for every gate, and is written here
    once.

    The JSON document carries `passed` — a verdict, not a status word — and
    the two counts of findings, so a reader knows which of them would have
    failed the build without re-reading the prose.
    """

    @click.command(name=name)
    @click.option("--quiet", is_flag=True, help="Findings only, for CI.")
    @click.option("--check", is_flag=True, help="Fail when a blocking finding is found.")
    @click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
    @click.option(
        "--write",
        "do_write",
        is_flag=True,
        help="Rewrite what the gate certifies, instead of checking it.",
    )
    @click.option(
        "--root",
        type=click.Path(file_okay=False, path_type=Path),
        default=default_root,
        help=root_help,
    )
    @click.pass_context
    def cli(
        ctx: click.Context,
        quiet: bool,
        check: bool,
        as_json: bool,
        root: Path,
        do_write: bool,
    ) -> None:
        """Report what the gate found."""
        if do_write:
            if write is None:
                click.echo("This gate has nothing to write.")
                raise ctx.exit(CONFIG_ERROR)
            raise ctx.exit(write())

        findings = report(root)
        blocking = blocking_findings(findings)
        advisory = advisory_findings(findings)

        if as_json:
            click.echo(
                json.dumps(
                    {
                        "passed": not blocking,
                        "blocking": len(blocking),
                        "advisory": len(advisory),
                        "findings": [
                            {"severity": f.severity, "message": f.message} for f in findings
                        ],
                    },
                    indent=2,
                )
            )
        else:
            if not quiet:
                click.echo(f"{name}: {title}")
            for finding in findings:
                tag = "drift" if finding.is_blocking else "wording"
                click.echo(f"  [{tag}] {finding.message}")
            if not quiet:
                click.echo()
                click.echo(
                    f"{len(blocking)} blocking finding(s), {len(advisory)} advisory."
                )
                if note:
                    click.echo(note)

        if blocking and check:
            raise ctx.exit(VALIDATION_FAILURE)
        raise ctx.exit(CLEAN)

    return cli
