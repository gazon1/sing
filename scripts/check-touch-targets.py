#!/usr/bin/env python3
"""check-touch-targets.py — a touch target is 48 dp, and the order of the modifiers decides it.

## What this gate holds

Material's minimum interactive size is 48 dp. A control smaller than that is a
target a finger cannot hit, and the way a control usually gets smaller is not
the size call itself — it is the *order* of the modifier chain:

    Modifier.size(40.dp).clickable { }      // the size caps the hit area
    Modifier.touchTarget().size(40.dp)      // the expansion is outside: 48 dp

`minimumInteractiveComponentSize()` — and the `touchTarget()` helper in
`core/ui/TouchTarget.kt` — has to be the **outermost** modifier. It then
expands the node to the minimum, and the `size(...)` that follows constrains
the visual size without touching the hit area.

So the rule is one sentence: **a chain that fixes a size below 48 dp and
carries an interactive modifier has to expand the target before that size.**

## What the first version of this gate could not see

The gate used to match `.size(...)` and `.clickable(` with two regular
expressions over the whole file. It was blind to every shape the codebase
actually writes:

* a chain spread over several lines — `re` does not match a newline with `.`
  unless it is told to, so `.size(40.dp)` on one line and `.clickable {` on
  the next were two unrelated strings;
* the lambda form of the call — the pattern ended in `clickable(`, and
  `.clickable { … }` has no parenthesis after the name;
* a file that used the helper *and* broke the rule — the gate skipped every
  file that mentioned `touchTarget` at all, so one correct control in a file
  hid every violation in that file.

The gate reported "no touch target below the minimum" on a corpus that
contains all three. It was registered, blocking, and blind, and the only
thing that could have said so was a control that plants a violation and
watches the gate go red: `scripts/tests/test_check_touch_targets.py` is that
control, one test per shape the gate claims to catch.

## What this gate cannot see

* A size that is not a literal (`size(iconSize)`). The gate reads literals; a
  computed size is a reviewer's job, not a regex's.
* `IconButton` and the other Material controls, which enforce their own
  minimum. A `.size()` on one of them constrains the icon, not the target.

Usage: python3 scripts/check-touch-targets.py [--json] [--quiet] [--root DIR]

`--root` names the tree to check, so a control can point the gate at a
fixture instead of at the working tree.

Exit codes:
  0 = every touch target is at least 48 dp
  1 = a touch target is below the minimum
  2 = the tree could not be read
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

#: Material's minimum interactive size. A fixed size below it needs an
#: expansion applied *before* it.
MIN_DP = 48.0

#: `.size(40.dp)` — a fixed size, in the only form the gate reads.
SIZE_RE = re.compile(r"\.size\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\)")

#: The interactive modifiers: a node with one of these is a target a finger
#: has to hit. The lambda form (`.clickable { … }`) and the parenthesised form
#: (`.clickable(onClick = …)`) are both here — the first version of this gate
#: matched only the second, which is why the lambda form was invisible.
INTERACTIVE_RE = re.compile(
    r"\.(?:clickable|toggleable|combinedClickable|selectable)\s*[({]"
)

#: The expansion. It has to come *before* the size in the chain: applied
#: after it, the size already caps the node and the expansion grows a target
#: that is smaller than the minimum.
EXPANSION_RE = re.compile(r"\.(?:touchTarget|minimumInteractiveComponentSize)\s*\(")

#: Build outputs: nothing in them ships to a device.
SKIPPED_DIRS = frozenset({"build"})

#: Test source sets: a target in a test never reaches a finger. The set is
#: named by its position in the path (`src/<source set>/`), NOT by any path
#: component called "test" — `shared/src/commonMain/kotlin/.../test/` is
#: production code in a package that happens to be called "test", and skipping
#: it would be the same blindness this gate was fixed for.
def _is_test_source(parts: tuple[str, ...]) -> bool:
    if "src" not in parts:
        return False
    index = parts.index("src")
    if index + 1 >= len(parts):
        return False
    source_set = parts[index + 1]
    return source_set == "test" or source_set.endswith("Test")


def _chains(lines: list[str]) -> list[tuple[int, str]]:
    """The modifier chains of a file, as (first line number, chain text).

    A chain is a head line plus the continuation lines that start with a dot —
    the shape Compose code is written in:

        modifier = Modifier
            .size(40.dp)
            .clickable { onClick() }

    The head line carries the `Modifier` and the continuation lines carry the
    modifiers, so the chain is read as one piece of text. Reading the file as
    one piece of text is the point: a chain that spans three lines is three
    lines of source and one chain of modifiers, and a gate that reads the
    lines separately sees two unrelated strings.
    """
    chains: list[tuple[int, str]] = []
    index = 0
    while index < len(lines):
        if lines[index].lstrip().startswith("."):
            # A continuation of a chain whose head was already read.
            index += 1
            continue
        chunk = [lines[index]]
        following = index + 1
        while following < len(lines) and lines[following].lstrip().startswith("."):
            chunk.append(lines[following])
            following += 1
        chains.append((index + 1, "\n".join(chunk)))
        index = following
    return chains


def _violations(chain: str) -> list[tuple[int, float]]:
    """The fixed sizes in a chain that make it a target below the minimum.

    Each entry is (offset of the size in the chain, the size in dp). A size is
    a violation when it is below the minimum, when the chain carries an
    interactive modifier, and when nothing expanded the target before it.
    """
    sizes = list(SIZE_RE.finditer(chain))
    if not sizes:
        return []
    if not INTERACTIVE_RE.search(chain):
        # Nothing to hit: a fixed size on a decorative node is not a target.
        return []
    expansions = [match.start() for match in EXPANSION_RE.finditer(chain)]
    found: list[tuple[int, float]] = []
    for match in sizes:
        value = float(match.group(1))
        if value >= MIN_DP:
            continue
        if any(offset < match.start() for offset in expansions):
            # The target was expanded before the size constrained it, so the
            # hit area is the minimum and the size only shapes the picture.
            continue
        found.append((match.start(), value))
    return found


def scan(root: pathlib.Path) -> list[dict[str, object]]:
    """Every touch target below the minimum in the tree, as findings."""
    findings: list[dict[str, object]] = []
    for path in sorted(root.rglob("*.kt")):
        relative = path.relative_to(root)
        if SKIPPED_DIRS.intersection(relative.parts):
            continue
        if _is_test_source(relative.parts):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for first_line, chain in _chains(text.splitlines()):
            for offset, value in _violations(chain):
                line = first_line + chain[:offset].count("\n")
                findings.append(
                    {
                        "file": relative.as_posix(),
                        "line": line,
                        "dp": value,
                        "detail": (
                            f"{value:g}dp is below the {MIN_DP:g}dp minimum, and no "
                            f"touchTarget()/minimumInteractiveComponentSize() comes "
                            f"before it in the chain"
                        ),
                    }
                )
    return findings


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.option("--quiet", is_flag=True, help="Print nothing when the tree is clean.")
@click.option(
    "--root",
    type=click.Path(file_okay=False, path_type=pathlib.Path),
    default=None,
    help="The tree to check (default: the repository this script lives in).",
)
@click.pass_context
def cli(ctx: click.Context, emit_json: bool, quiet: bool, root: pathlib.Path | None) -> None:
    """Every interactive target is at least 48 dp, and the expansion comes first."""
    tree = (root or ROOT).resolve()
    if not tree.is_dir():
        click.echo(f"check-touch-targets.py: {tree} is not a directory", err=True)
        raise ctx.exit(IO_ERROR)

    findings = scan(tree)

    if emit_json:
        click.echo(json.dumps({"passed": not findings, "findings": findings}, indent=2))
    elif findings:
        click.echo(f"check-touch-targets.py: {len(findings)} touch target(s) below the minimum")
        for finding in findings:
            click.echo(
                f"  {finding['file']}:{finding['line']}: {finding['detail']}"
            )
    elif not quiet:
        click.echo(f"check-touch-targets.py: every touch target is at least {MIN_DP:g}dp")

    raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
