#!/usr/bin/env python3
"""check-touch-targets.py — a touch target is 48 dp, and the order of the modifiers decides it.

## What this gate holds

Material's minimum interactive size is 48 dp. A control smaller than that is a
target a finger cannot hit, and the way a control usually gets smaller is not
the size call itself — it is the *order* of the modifier chain:

    Modifier.size(40.dp).clickable { }      // the size caps the hit area
    Modifier.touchTarget().size(40.dp)      // the expansion is outside: 48 dp

`Modifier.touchTarget(minSize)` — the project's helper in
`core/ui/TouchTarget.kt` — has to be the **outermost** modifier. It then
expands the node to the minimum, and the `size(...)` that follows constrains
the visual size without touching the hit area.

Material's own `minimumInteractiveComponentSize()` is accepted in the same
position, because a chain that legally calls it is not a violation — but it is
not the call to write. In the pinned material3 it takes no size argument (the
minimum is a composition local), so a call site here writes
`Modifier.touchTarget(minSize)`. See `CODING_STANDARDS.md`, "Touch targets meet
the minimum and the order is correct".

So the rule is one sentence: **a chain that fixes a size below 48 dp and
carries an interactive modifier has to expand the target to the minimum,
before that size.**

The expansion has to *name* the minimum as well: `Modifier.touchTarget(40.dp)`
expands the target to 40 dp, which is the size the rule forbids. An expansion
that takes no argument names the helper's default — `TouchTarget.MinSize`,
the shared 48 dp — and an expansion that names that token says the same thing.

The gate reads the three forms a chain fixes its size in: `.size(N.dp)` sets
both dimensions, `.width(N.dp)` and `.height(N.dp)` set one of them each. Each
of them caps the node, and a node capped below the minimum is a target a
finger cannot hit.

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

## What the second version of this gate could not see

The second version read the chain properly and still read only `.size(...)`.
A chain that fixed its width or its height — `.width(24.dp)`,
`.height(24.dp)` — was invisible to it, and the codebase writes those shapes: a
control that fixes one dimension is as small as a control that fixes both. The
gate reads all three now, and `scripts/tests/test_check_touch_targets.py`
plants each of them: a width below the minimum and a height below the minimum
are both reported, and the chain that expands the target before the size is
not.

## What the third version of this gate could not see

The third version read the chain properly, read the three forms a size takes
and read the order of the chain — and still read only the *shape* of the
expansion: `.touchTarget(` and `.minimumInteractiveComponentSize(` matched,
and what was inside the parenthesis was never looked at. So a chain that
expanded the target to a minimum below 48 dp counted as correctly expanded:

    Modifier.touchTarget(40.dp).size(40.dp)   // the target is 40 dp

The gate reads the argument now. An expansion that names a number below the
minimum is a target below the minimum, and it is reported the way a small
fixed size is reported — the file, the line and the value. An expansion that
takes no argument names the helper's default, which is the shared minimum,
and an expansion that names a token the gate cannot read is a reviewer's job:
the same rule the gate already follows for a size it cannot read.

## What this gate reads

The gate reads **production sources**. A target in a test never reaches a
finger, and a Compose harness test mounts small clickable boxes constantly —
that is what a harness is — so a violation in a test source set is not a
defect, it is the shape of a test.

Test source sets are told apart by their **position in the path**
(`src/<source set>/`): a source set called `test`, and every source set whose
name ends in `Test` — `jvmTest`, `commonTest`, `androidHostTest`,
`androidTest`. The name of the source set is the Gradle convention, so the
name is what the gate asks about. The exclusion is deliberately not a path
component called `test`: `shared/src/commonMain/kotlin/.../test/` is production
code in a package that happens to be called `test`, and skipping it would have
the same blindness as the lowercase `/test/` substring this replaced.

The scope has a control of its own, in
`scripts/tests/test_check_touch_targets.py`: the same 40dp box is planted in
every test root of this repository and in every production root, and the gate
has to be green on the first and red on the second. A filter that is not
planted against is a filter that can rot unnoticed.

## What this gate cannot see

* A size that is not a literal (`size(iconSize)`, `width(iconSize)`, a size
  carried by a constant). The gate reads literals; a computed size is a
  reviewer's job, not a regex's.
* A size in a form the gate does not read (`requiredSize(40.dp)`,
  `requiredWidth(24.dp)`). Those cap the node as hard as the three forms
  above; the gate reads the three the codebase writes, and the rest is a
  reviewer's job.
* An expansion whose argument is not a number (`touchTarget(MinSize)`,
  `touchTarget(computed)`). The gate cannot resolve a token, so it takes the
  expansion to name the minimum; a token that names a smaller value is a
  reviewer's job, the same as a size the gate cannot read.
* `IconButton` and the other Material controls, which enforce their own
  minimum. A size on one of them constrains the icon, not the target.

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

#: A fixed size, in every form the gate reads: `.size(N.dp)`, `.width(N.dp)`
#: and `.height(N.dp)`. All three fix the hit area of the node. The modifier
#: is captured as well as the value, so a finding can name the call that made
#: the target too small.
SIZE_RE = re.compile(
    r"\.(size|width|height)\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\)"
)

#: The interactive modifiers: a node with one of these is a target a finger
#: has to hit. The lambda form (`.clickable { … }`) and the parenthesised form
#: (`.clickable(onClick = …)`) are both here — the first version of this gate
#: matched only the second, which is why the lambda form was invisible.
INTERACTIVE_RE = re.compile(
    r"\.(?:clickable|toggleable|combinedClickable|selectable)\s*[({]"
)

#: The expansion, with the argument it names. It has to come *before* the
#: size in the chain: applied after it, the size already caps the node and
#: the expansion grows a target that is smaller than the minimum.
#:
#: The argument is read as well as the position. `.touchTarget(40.dp)` names
#: a target below the minimum, and a gate that matched the call's shape
#: alone counted it as correctly expanded — the chain looked right and the
#: hit area was 40 dp.
EXPANSION_RE = re.compile(
    r"\.(?P<call>touchTarget|minimumInteractiveComponentSize)\s*"
    r"\((?P<argument>[^)]*)\)"
)

#: A minimum in dp, as the gate reads it in an expansion's argument: the
#: number and the unit, with or without a space between them.
DP_LITERAL_RE = re.compile(r"(\d+(?:\.\d+)?)\s*\.dp")

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


def _expansion_minimum(match: re.Match[str]) -> float | None:
    """The minimum an expansion names, in dp — None when it names none.

    An expansion that takes no argument names the helper's default, which is
    the shared minimum. An argument the gate cannot read — a token, a computed
    value — is None as well: the gate reads literals, and a value it cannot
    read is a reviewer's job, exactly as for a size it cannot read. What it
    can read is a number, and a number below the minimum is a target a finger
    cannot hit.
    """
    argument = match.group("argument").strip()
    if not argument:
        return None
    # A named argument carries the value after the `=`.
    _, separator, value = argument.partition("=")
    literal = DP_LITERAL_RE.fullmatch(value.strip() if separator else argument)
    if literal is None:
        return None
    return float(literal.group(1))


def _expands_to_the_minimum(match: re.Match[str]) -> bool:
    """Whether an expansion expands the target to the minimum.

    An expansion that names no minimum at all — the helper's default, or a
    token the gate cannot read — is taken to name the shared minimum; one
    that names a number below the minimum does not.
    """
    minimum = _expansion_minimum(match)
    return minimum is None or minimum >= MIN_DP


def _violations(chain: str) -> list[tuple[int, float, str]]:
    """The findings in a chain, as (offset in the chain, the value in dp, the
    detail of the finding).

    A finding is one of two things: a fixed size below the minimum that
    nothing expanded to the minimum before it, and an expansion that names a
    minimum below the minimum — a target the chain never grows past.
    """
    if not INTERACTIVE_RE.search(chain):
        # Nothing to hit: a fixed size on a decorative node is not a target.
        return []

    expansions = list(EXPANSION_RE.finditer(chain))
    found: list[tuple[int, float, str]] = []

    # An expansion that names a minimum below the minimum: the chain asks for
    # a target smaller than the one the rule asks for, whatever the order of
    # the chain is.
    named_below_minimum: list[float] = []
    for match in expansions:
        value = _expansion_minimum(match)
        if value is None or value >= MIN_DP:
            continue
        named_below_minimum.append(value)
        found.append(
            (
                match.start(),
                value,
                f"{match.group('call')}({value:g}dp) expands the target to "
                f"{value:g}dp, which is below the {MIN_DP:g}dp minimum",
            )
        )

    for match in SIZE_RE.finditer(chain):
        value = float(match.group(2))
        if value >= MIN_DP:
            continue
        if value in named_below_minimum:
            # The expansion named this value and was reported already.
            continue
        if any(
            match.start() > expansion.start()
            for expansion in expansions
            if _expands_to_the_minimum(expansion)
        ):
            # The target was expanded to the minimum before the size
            # constrained it, so the hit area is the minimum and the size
            # only shapes the picture.
            continue
        found.append(
            (
                match.start(),
                value,
                f"{match.group(1)}({value:g}dp) is below the {MIN_DP:g}dp "
                f"minimum, and no expansion to the minimum comes before it "
                f"in the chain",
            )
        )
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
            for offset, value, detail in _violations(chain):
                line = first_line + chain[:offset].count("\n")
                findings.append(
                    {
                        "file": relative.as_posix(),
                        "line": line,
                        "dp": value,
                        "detail": detail,
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
