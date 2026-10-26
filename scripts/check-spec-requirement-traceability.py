#!/usr/bin/env python3
"""check-spec-requirement-traceability.py — every requirement in a spec is traced, and every check a spec promises exists.

Two claims a specification makes about itself, and nothing else.

1. **Every requirement is accounted for.** A feature's `spec.md` declares its
   functional requirements and then carries a traceability table saying what
   exercises each of them. A requirement that is in neither the table nor an
   explicit exception is a requirement nobody looked at — and because the table
   is prose, a requirement dropped out of it silently. The failure this catches
   is the one the convergence pass of the external-agent feature found: two
   requirements (the lifecycle log and the mobile isolation) that the artifacts
   claimed were covered by "an automated check" that did not exist.

2. **Every promised check exists.** When the traceability table says a
   requirement is exercised by an automated check, it names that check. A name
   that is not a file in the tree is a promise about a check nobody wrote — and
   it reads exactly like a promise about a check that does exist.

What the gate does not do
--------------------------

It does not decide *whether a check is good*. It knows that a file named
`AcpSessionEventTest` exists; it cannot know that the test behind it asserts
what the requirement says. That judgement belongs to the review, and to the
gate that owns the requirement's behaviour. This gate's job is the narrower and
more mechanical one: that the artifacts and the tree agree with each other.

A feature with no traceability table is reported as skipped, not as failed:
the table is a convention, not a law, and a feature that never adopted it has
not broken anything by its absence.

Usage:
    python3 scripts/check-spec-requirement-traceability.py
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `sgate` against *this* checkout, not
# against whatever `sgate` happens to be installed.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

SPECS_DIR = ROOT / "specs"

# A declared requirement: a bullet that starts with the requirement's id.
DECLARED = re.compile(r"^\s*[-*]\s+\*\*(FR-\d+)\*\*\s*:", re.MULTILINE)

# The traceability table's first column: requirement ids and ranges of them.
TABLE_ROW = re.compile(r"^\|\s*(FR-[^|]+)\|", re.MULTILINE)

# A range inside that column: "FR-001 – FR-005".
RANGE = re.compile(r"FR-(\d+)\s*[-–—]\s*FR-(\d+)")

# An id inside that column.
ONE = re.compile(r"FR-\d+")

# A backticked token — the thing a table names as the check that covers a row.
PROMISED = re.compile(r"`([A-Za-z0-9_./-]+)`")


def is_promised_check(name: str) -> bool:
    """Whether a backticked token claims to be a check.

    Two shapes count: a file path (`SomethingTest.kt`, `scripts/check-x.py`), and
    a test class named without its file (`SomethingTest`) — which is how a
    specification usually names one, and how the external-agent feature's own
    table names its lifecycle check.

    A token that is neither (`getOrElse`, `User Story 1`) is prose, not a
    promise, and is left alone.
    """
    if name.endswith((".kt", ".py", ".sh")):
        return True
    return name.endswith("Test") and name[:1].isupper()


def known_files(tree: pathlib.Path) -> set[str]:
    """Every file in the tree, by repo-relative path and by bare name.

    The tree is walked rather than read from the git index: the gate asks
    "does this name a file that exists", and a file that exists but is not
    tracked yet is still a file — a spec written before the file is added is
    not wrong about the file.
    """
    paths: set[str] = set()
    for path in tree.rglob("*"):
        if not path.is_file():
            continue
        if any(part in {"build", ".gradle", ".git", "node_modules"} for part in path.parts):
            continue
        try:
            paths.add(path.relative_to(tree).as_posix())
        except ValueError:
            continue
    names = {pathlib.PurePosixPath(p).name for p in paths}
    # The stem as well: a specification names a test by its class name
    # ("AcpSessionEventTest"), and the class lives in a file with an extension.
    stems = {name.rsplit(".", 1)[0] for name in names if "." in name}
    return paths | names | stems


def expand(cell: str) -> set[str]:
    """The requirement ids a traceability cell names, ranges expanded."""
    def expand_range(match: re.Match[str]) -> str:
        low, high = int(match.group(1)), int(match.group(2))
        if high < low:
            low, high = high, low
        return " ".join(f"FR-{n:03d}" for n in range(low, high + 1))

    without_ranges = RANGE.sub(expand_range, cell)
    return set(ONE.findall(without_ranges))


def check_feature(spec: pathlib.Path, known: set[str], tree: pathlib.Path) -> tuple[list[str], bool]:
    """What is wrong with one feature's specification, and whether it has a table."""
    text = spec.read_text(encoding="utf-8")
    problems: list[str] = []

    declared = set(DECLARED.findall(text))
    if not declared:
        return problems, False

    rows = TABLE_ROW.findall(text)
    if not rows:
        return problems, False

    covered: set[str] = set()
    for row in rows:
        covered |= expand(row)

    for requirement in sorted(declared - covered):
        problems.append(
            f"{spec.relative_to(tree).as_posix()}: {requirement} is declared but no "
            f"traceability row covers it",
        )

    for promised in sorted(set(PROMISED.findall(text))):
        if not is_promised_check(promised):
            continue
        name = promised.rsplit("/", 1)[-1]
        if promised in known or name in known or name.rsplit(".", 1)[0] in known:
            continue
        problems.append(
            f"{spec.relative_to(tree).as_posix()}: the spec promises the check "
            f"`{promised}`, which is not a file in the tree",
        )

    return problems, True


@click.command()
@click.option(
    "--check",
    is_flag=True,
    help="Check the tree and exit non-zero on a finding (the default; the flag is what the gate runner passes)",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON")
@click.option(
    "--root",
    default=None,
    help="the tree to check (default: the repository this script lives in)",
)
@click.pass_context
def cli(ctx: click.Context, check: bool, emit_json: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    specs_dir = tree / "specs"
    if not specs_dir.exists():
        click.echo(f"ERROR: {specs_dir} does not exist — there is nothing to check")
        raise ctx.exit(VALIDATION_FAILURE)

    known = known_files(tree)

    problems: list[str] = []
    checked = 0
    skipped = 0
    for spec in sorted(specs_dir.glob("*/spec.md")):
        found, has_table = check_feature(spec, known, tree)
        problems.extend(found)
        if has_table:
            checked += 1
        else:
            skipped += 1

    if problems:
        if emit_json:
            click.echo(
                json.dumps(
                    {
                        "gate": "spec-requirement-traceability",
                        "verdict": "fail",
                        "traced": checked,
                        "findings": problems,
                    },
                ),
            )
        else:
            click.echo("A specification and the tree must agree:")
            for problem in problems:
                click.echo(f"  - {problem}")
        raise ctx.exit(VALIDATION_FAILURE)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "gate": "spec-requirement-traceability",
                    "verdict": "pass",
                    "traced": checked,
                    "findings": [],
                },
            ),
        )
    else:
        click.echo(
            f"check-spec-requirement-traceability: OK — {checked} traced feature "
            f"specification(s) agree with the tree, {skipped} without a table skipped",
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
