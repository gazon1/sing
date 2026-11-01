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

## What a chain is

A chain is the head line plus the modifier calls that belong to it. The calls
are read as ONE piece of text, because a chain that spans three lines is three
lines of source and one chain of modifiers, and a gate that reads the lines
separately sees two unrelated strings.

A chain also survives a **block**. `.semantics { … }` puts its body on lines
that do not start with a dot, and the first version of this gate stopped there
— so every call after such a block was in no chain at all, and a chain whose
`clickable` sat behind a `semantics` block was invisible to it. The chain now
continues while a brace is open.

## What this gate cannot see

* A size that is not a literal (`size(iconSize)`). The gate reads literals; a
  computed size is a reviewer's job, not a regex's. Such a chain is reported
  as **unjudged** rather than as a pass: the gate does not know whether the
  size is below the minimum, and a chain it did not judge is not a chain that
  passed. See "What unjudged means" below.
* `IconButton` and the other Material controls, which enforce their own
  minimum. A `.size()` on one of them constrains the icon, not the target.

## What unjudged means

Three verdicts, and they are not two:

* **pass** — the chain was read and every target in it is at least the minimum;
* **violation** — the chain was read and a target in it is below the minimum;
* **unjudged** — the chain carries a size the gate cannot read, so the gate
  cannot say whether that target is below the minimum.

An unjudged chain is not a violation, and it is not a pass either. It is the
blind spot made visible: the gate says it did not judge that chain rather than
counting it as one more target that is fine. A computed size stays a
reviewer's job, but the reviewer has to be told there is one.

The run does not claim the tree is clean while it holds unjudged chains: the
summary says how many chains it could not judge, and the exit code is the
failure of the chains it *could* judge.

Usage: python3 scripts/check-touch-targets.py [--json] [--quiet] [--root DIR]

`--root` names the tree to check, so a control can point the gate at a
fixture instead of at the working tree.

Exit codes:
  0 = every touch target the gate could judge is at least 48 dp
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

#: `.size(40.dp)`, `.width(40.dp)`, `.height(40.dp)` — a fixed size, in the
#: only form the gate reads.
#:
#: `.width()` and `.height()` are sizes too: a chain that fixes one of them
#: below the minimum has a target below the minimum, whatever it fixed the
#: other one to. The gate read only `.size()` for a while, so a `.height(40.dp)`
#: row with a `clickable` on it was invisible — the row is a target, and its
#: height is what a finger has to hit.
SIZE_RE = re.compile(r"\.(?:size|width|height)\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\)")

#: A size call whose value the gate cannot read: `.size(iconSize)`.
#:
#: The chain carries a size the gate cannot resolve, so the chain is unjudged
#: rather than passed. It is not a violation: a computed size may well be
#: 48 dp or more. It is not a pass either, because the gate did not read it.
UNRESOLVED_SIZE_RE = re.compile(r"\.(?:size|width|height)\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)")

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
#:
#: The argument is captured because the expansion's own size is part of the
#: claim: `touchTarget(24.dp)` asks for less than the minimum, so it does not
#: expand the target to the minimum, and the size it precedes is still a
#: violation. `touchTarget()` with no argument asks for the default — the
#: minimum — and `touchTarget(MinTouchTarget)` names a constant the gate
#: cannot read, which is treated as the minimum it is.
EXPANSION_RE = re.compile(
    r"\.(?:touchTarget|minimumInteractiveComponentSize)\s*\(([^)]*)\)"
)

#: An expansion argument that is a literal size.
EXPANSION_LITERAL_RE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*\.dp\s*$")

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

    A chain also survives a **block**. `.semantics { … }` puts its body on
    lines that do not start with a dot, so a chain that stopped at the first
    of them ended before the calls that came after:

        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = label }
            .clickable { onClick() }      <- in no chain at all

    The size was in one chain and the clickable in another, so the gate saw a
    size that was not a target — and a target it could not see. The chain
    therefore continues while a brace is open.
    """
    chains: list[tuple[int, str]] = []
    index = 0
    while index < len(lines):
        if lines[index].lstrip().startswith("."):
            # A continuation of a chain whose head was already read.
            index += 1
            continue
        chunk = [lines[index]]
        depth = 0
        following = index + 1
        while following < len(lines):
            line = lines[following]
            if not line.lstrip().startswith(".") and depth <= 0:
                break
            chunk.append(line)
            depth += line.count("{") - line.count("}")
            following += 1
        chains.append((index + 1, "\n".join(chunk)))
        index = following
    return chains


def _expansions(chain: str) -> list[tuple[int, bool]]:
    """The expansions in a chain, as (offset, reaches the minimum).

    The second half of the pair is the question the gate could not answer
    before: an expansion that asks for LESS than the minimum does not expand
    the target to the minimum, so the size it precedes is still a violation.
    `touchTarget(24.dp)` is that shape.

    An argument the gate cannot read is treated as reaching the minimum: it
    names a constant, and the constant this repository uses is the minimum.
    Resolving Kotlin to find out is a different job than this gate's.
    """
    found: list[tuple[int, bool]] = []
    for match in EXPANSION_RE.finditer(chain):
        argument = match.group(1).strip()
        literal = EXPANSION_LITERAL_RE.match(argument)
        reaches = literal is None or float(literal.group(1)) >= MIN_DP
        found.append((match.start(), reaches))
    return found


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
    expansions = _expansions(chain)
    found: list[tuple[int, float]] = []
    for match in sizes:
        value = float(match.group(1))
        if value >= MIN_DP:
            continue
        if any(offset < match.start() and reaches for offset, reaches in expansions):
            # The target was expanded to the minimum before the size
            # constrained it, so the hit area is the minimum and the size
            # only shapes the picture.
            continue
        found.append((match.start(), value))
    return found


def _unjudged(chain: str) -> list[tuple[int, str]]:
    """The sizes in a chain the gate cannot read, as (offset, the expression).

    A chain that carries a size the gate cannot resolve is UNJUDGED: the gate
    cannot say whether that target is below the minimum, so it does not count
    the chain as one that passed. It is not a violation either — a computed
    size may well be 48 dp or more, and resolving Kotlin is a reviewer's job.

    The blind spot this makes visible is the one the gate had before: a chain
    that sized itself with a parameter, with its expansion after the size, was
    reported as fine. It was not fine — it was unread.
    """
    if not INTERACTIVE_RE.search(chain):
        # Nothing to hit: a computed size on a decorative node is not a target.
        return []
    expansions = _expansions(chain)
    found: list[tuple[int, str]] = []
    for match in UNRESOLVED_SIZE_RE.finditer(chain):
        if any(offset < match.start() and reaches for offset, reaches in expansions):
            # The target was expanded before the size constrained it, so the
            # hit area is the minimum whatever the size turns out to be.
            continue
        found.append((match.start(), match.group(1)))
    return found


def scan(root: pathlib.Path) -> list[dict[str, object]]:
    """Every touch target below the minimum in the tree, as findings.

    A finding carries a ``kind``: ``violation`` for a target the gate read and
    found too small, ``unjudged`` for a target whose size the gate could not
    read. Both are findings, and the difference is what a reader has to do
    about them: a violation is a defect, an unjudged one is a size a reviewer
    has to look at.
    """
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
                        "kind": "violation",
                        "dp": value,
                        "detail": (
                            f"{value:g}dp is below the {MIN_DP:g}dp minimum, and no "
                            f"touchTarget()/minimumInteractiveComponentSize() comes "
                            f"before it in the chain"
                        ),
                    }
                )
            for offset, expression in _unjudged(chain):
                line = first_line + chain[:offset].count("\n")
                findings.append(
                    {
                        "file": relative.as_posix(),
                        "line": line,
                        "kind": "unjudged",
                        "dp": None,
                        "detail": (
                            f"the size '{expression}' is not a literal, so the gate "
                            f"cannot say whether this target is below the "
                            f"{MIN_DP:g}dp minimum — a reviewer has to look at it"
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
    violations = [f for f in findings if f["kind"] == "violation"]
    unjudged = [f for f in findings if f["kind"] == "unjudged"]

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "findings": findings,
                "violations": violations,
                "unjudged": unjudged,
            },
            indent=2,
        ))
    else:
        if violations:
            click.echo(
                f"check-touch-targets.py: {len(violations)} touch target(s) below the minimum"
            )
            for finding in violations:
                click.echo(f"  {finding['file']}:{finding['line']}: {finding['detail']}")
        if unjudged:
            # The blind spot, made visible: the gate did not read these, so it
            # does not count them as targets it has checked.
            click.echo(
                f"check-touch-targets.py: {len(unjudged)} chain(s) the gate could not judge"
            )
            for finding in unjudged:
                click.echo(f"  {finding['file']}:{finding['line']}: {finding['detail']}")
        if not violations and not quiet:
            # The summary must not claim the tree is clean while the gate holds
            # chains it did not read.
            if unjudged:
                click.echo(
                    f"check-touch-targets.py: no touch target below the {MIN_DP:g}dp "
                    f"minimum in the chains it could judge"
                )
            else:
                click.echo(
                    f"check-touch-targets.py: every touch target is at least {MIN_DP:g}dp"
                )

    raise ctx.exit(CLEAN if not violations else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
