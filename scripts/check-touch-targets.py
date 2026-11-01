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

## What a chain is

A chain is a run of modifier calls joined by dots — the calls that belong to
one `Modifier`. The gate cuts the source into chains and decides **per
chain**, never per file and never per line:

* a chain may span several lines, because the calls are joined by dots and
  not by line breaks;
* two chains may share a line, and they are then two chains — a correct
  chain and a violating one on the same line are two chains, and the correct
  one does not speak for the other;
* a chain written inside another call's arguments is its own chain.

The exemption therefore belongs to the chain: a chain that expands the target
before the size is fine, and the other chains in the same file are not. The
first version of the gate exempted the *file*, which is why the fourteen
files that had been migrated were the fourteen files the gate never looked
at.

Comments and string literals are blanked out before the source is cut, so a
violation mentioned in a KDoc is not a violation, and a `.dp` inside a string
is not a size.

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
from typing import NamedTuple

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

#: The interactive modifiers: a node with one of these is a target a finger
#: has to hit. The lambda form (`.clickable { … }`) and the parenthesised form
#: (`.clickable(onClick = …)`) are both here — the first version of this gate
#: matched only the second, which is why the lambda form was invisible.
INTERACTIVE_CALLS = frozenset(
    {"clickable", "toggleable", "combinedClickable", "selectable"}
)

#: The expansion. It has to come *before* the size in the chain: applied
#: after it, the size already caps the node and the expansion grows a target
#: that is smaller than the minimum.
EXPANSION_CALLS = frozenset({"touchTarget", "minimumInteractiveComponentSize"})

#: A modifier call: the dot, the name, and — separately — the brackets that
#: carry its arguments.
CALL = re.compile(r"\.([A-Za-z_][A-Za-z0-9_]*)")

#: The body of a `.size(...)` call: a literal size in dp, and nothing else.
SIZE_BODY = re.compile(r"^\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\)$")

#: Build outputs: nothing in them ships to a device.
SKIPPED_DIRS = frozenset({"build"})

#: The brackets that carry a call's arguments.
BRACKETS = {"(": ")", "{": "}"}


class ModifierCall(NamedTuple):
    """One modifier call: its name, where it starts, and what it carries."""

    name: str
    start: int
    end: int
    body: str = ""


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


def _blank(out: list[str], start: int, end: int) -> None:
    """Replace the given range with spaces, leaving the newlines in place."""
    for position in range(start, min(end, len(out))):
        if out[position] != "\n":
            out[position] = " "


def _end_of_block_comment(text: str, start: int) -> int:
    """The index just after the block comment that starts at ``start``."""
    depth = 0
    index = start
    while index < len(text):
        if text.startswith("/*", index):
            depth += 1
            index += 2
            continue
        if text.startswith("*/", index):
            depth -= 1
            index += 2
            if depth == 0:
                return index
            continue
        index += 1
    return len(text)


def _end_of_string(text: str, start: int) -> int:
    """The index just after the string literal that starts at ``start``."""
    if text.startswith('"""', start):
        end = text.find('"""', start + 3)
        return len(text) if end == -1 else end + 3
    index = start + 1
    while index < len(text) and text[index] != '"':
        index += 2 if text[index] == "\\" else 1
    return min(index + 1, len(text))


def _end_of_char(text: str, start: int) -> int:
    """The index just after the character literal that starts at ``start``."""
    index = start + 1
    while index < len(text) and text[index] != "'":
        index += 2 if text[index] == "\\" else 1
    return min(index + 1, len(text))


def _end_of_bracket(text: str, start: int) -> int:
    """The index just after the bracket that closes the one at ``start``."""
    opener = text[start]
    closer = BRACKETS[opener]
    depth = 0
    index = start
    while index < len(text):
        char = text[index]
        if char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return len(text)


def blank_noncode(text: str) -> str:
    """Replace comments and literals with spaces, keeping every offset.

    A violation mentioned in a comment or written in a string is not a
    violation, and a blanked source keeps the line numbers of the findings
    honest: the blanks are spaces, so every offset in the blanked text is
    the offset it had in the source.
    """
    out = list(text)
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end == -1 else end
            _blank(out, index, end)
            index = end
        elif text.startswith("/*", index):
            end = _end_of_block_comment(text, index)
            _blank(out, index, end)
            index = end
        elif char == '"':
            end = _end_of_string(text, index)
            _blank(out, index, end)
            index = end
        elif char == "'":
            end = _end_of_char(text, index)
            _blank(out, index, end)
            index = end
        else:
            index += 1
    return "".join(out)


def chains(text: str, start: int = 0, end: int | None = None) -> list[list[ModifierCall]]:
    """Every modifier chain in the text, each as the list of its calls.

    Two calls belong to the same chain when nothing but whitespace stands
    between them — the dot that joins them is the one that opens the next
    call. A chain may therefore span several lines, and two chains in one
    line are two chains, whatever the file looks like.

    A chain written inside another call's arguments is its own chain: the
    modifier of a nested expression is not the modifier of the expression
    around it.
    """
    if end is None:
        end = len(text)
    found: list[list[ModifierCall]] = []
    chain: list[ModifierCall] = []
    position = start
    while position < end:
        match = CALL.search(text, position, end)
        if match is None:
            break
        name = match.group(1)
        cursor = match.end()
        while cursor < end and text[cursor] in " \t\r\n":
            cursor += 1
        body = ""
        call_end = match.end()
        if cursor < end and text[cursor] in "({":
            call_end = _end_of_bracket(text, cursor)
            body = text[cursor:call_end]
            if name != "size":
                # A chain written inside a call's arguments is its own chain.
                # A size carries a literal, so there is nothing to find in it.
                found.extend(chains(text, cursor + 1, call_end - 1))
        if chain and text[chain[-1].end : match.start()].strip():
            found.append(chain)
            chain = []
        chain.append(
            ModifierCall(name=name, start=match.start(), end=call_end, body=body)
        )
        position = max(call_end, match.end())
    if chain:
        found.append(chain)
    return found


def violations_in(source: str) -> list[tuple[int, float]]:
    """The violations in one Kotlin source, as (line, size in dp)."""
    found: list[tuple[int, float]] = []
    for chain in chains(source):
        interactive = any(
            call.name in INTERACTIVE_CALLS and call.body for call in chain
        )
        if not interactive:
            # Nothing to hit: a fixed size on a decorative node is not a target.
            continue
        expansions = [
            call.start for call in chain if call.name in EXPANSION_CALLS and call.body
        ]
        for call in chain:
            if call.name != "size":
                continue
            match = SIZE_BODY.match(call.body)
            if match is None:
                # A size that is not a literal is a reviewer's job.
                continue
            value = float(match.group(1))
            if value >= MIN_DP:
                continue
            if any(offset < call.start for offset in expansions):
                # The target was expanded before the size constrained it, so
                # the hit area is the minimum and the size only shapes the
                # picture.
                continue
            found.append((source.count("\n", 0, call.start) + 1, value))
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
        source = blank_noncode(text)
        for line, value in violations_in(source):
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
