#!/usr/bin/env python3
"""check-touch-targets.py — a touch target below the minimum is a target nobody can hit.

What went wrong
---------------
A screen can be correct in every way the eye can check — the layout is right,
the contrast is right, the label reads well — and still be unusable with a
thumb. An icon that occupies 32dp is a target that a thumb misses, and the
miss looks like the app did nothing: the tap landed on the background, the
screen did not change, and the person concludes that the feature is broken.

Material ships the answer for this — `minimumInteractiveComponentSize` and
`Modifier.touchTarget` both exist — and a file that uses neither of them is
a file that has not made the decision. This gate finds the interactive
elements whose size is set explicitly and left below 48dp, which is the
minimum Material declares for a touch target.

The rule
--------
An interactive composable (`.clickable`, `.toggleable`) whose size is set
explicitly to less than 48dp is a finding, unless the file says otherwise by
using `touchTarget` or `minimumInteractiveComponentSize` — the two APIs that
make the target the right size whatever the caller wrote.

What this gate cannot see
-------------------------
The patterns match one line at a time, so a size and an interactive modifier
on different lines are two unrelated strings; and they match the call form
`.clickable(`, which the lambda form `.clickable { }` does not have. A file
that mentions `touchTarget` anywhere is exempt as a whole, so one correct
control in a file hides every violation in that file.

Exit codes
----------
0 — clean
1 — a finding
2 — the tree could not be read (an I/O error)
5 — an internal error

Usage:
    python3 scripts/check-touch-targets.py
    python3 scripts/check-touch-targets.py --quiet
    python3 scripts/check-touch-targets.py --json
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

import click

ROOT = pathlib.Path(__file__).resolve().parent.parent

# This checkout's package root, before the import that reads this checkout's
# conventions. A bootstrap that comes after the import it serves is a no-op
# for that import: the name is already bound, and the gate runs against
# another checkout's code (R2.5, R2.6).
sys.path.insert(0, str(ROOT / "scripts" / "src"))

from sgate.commands.base import (  # noqa: E402
    CLEAN,
    INTERNAL_ERROR,
    IO_ERROR,
    VALIDATION_FAILURE,
    run,
)

#: The smallest touch target Material declares.
MINIMUM_DP = 48.0

#: The patterns that find an interactive element whose size is set explicitly.
PATTERNS = [
    (
        re.compile(r"\.size\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\).*?\.clickable\("),
        "size before clickable",
    ),
    (
        re.compile(r"\.size\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\).*?\.toggleable\("),
        "size before toggleable",
    ),
]


def find_violations(root: pathlib.Path) -> list[str]:
    """The touch targets below the minimum; empty when there are none.

    @param root the tree to scan.
    @return one finding per interactive element below the minimum.
    """
    findings: list[str] = []
    for path in root.rglob("*.kt"):
        text_path = str(path)
        if "/build/" in text_path or "/test/" in text_path:
            continue
        text = path.read_text()
        if "touchTarget" in text or "minimumInteractiveComponentSize" in text:
            continue
        for pattern, message in PATTERNS:
            for match in pattern.finditer(text):
                try:
                    value = float(match.group(1))
                except ValueError:
                    continue
                if value < MINIMUM_DP:
                    relative = path.relative_to(root)
                    findings.append(f"{relative}: {message} ({value}dp)")
    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Print nothing when the gate is clean.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, emit_json: bool) -> None:
    """Fail when an interactive element is smaller than the minimum touch target."""
    try:
        findings = find_violations(ROOT)
    except (OSError, UnicodeDecodeError) as error:
        message = f"the tree could not be read: {error}"
        if emit_json:
            click.echo(
                json.dumps(
                    {"passed": False, "blocking_errors": [message], "warnings": []},
                    ensure_ascii=False,
                )
            )
        else:
            click.echo(f"ERROR: {message}", err=True)
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "blocking_errors": findings,
                    "warnings": [],
                },
                ensure_ascii=False,
            )
        )
        raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)

    for finding in findings:
        click.echo(f"ERROR: {finding}")
    if findings:
        click.echo("")
        click.echo(
            "check-touch-targets: an interactive element has to be at least "
            f"{MINIMUM_DP:.0f}dp across — or say so with touchTarget / "
            "minimumInteractiveComponentSize."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            "check-touch-targets: OK — every interactive element is at least "
            f"{MINIMUM_DP:.0f}dp across."
        )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    """The gate's entry point; the exit code the registry reads."""
    try:
        return run(cli.main, argv)
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 — an internal error is an exit code, not a traceback
        click.echo(f"check-touch-targets: internal error: {exc}", err=True)
        return INTERNAL_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
