#!/usr/bin/env python3
"""check-touch-target-token.py — a touch target outside the task list is expanded with the shared token, and with nothing else.

## What this gate holds

The minimum touch target has one home: `TouchTarget.MinSize` in the core UI
package (`core/ui/TouchTarget.kt`). `Modifier.touchTarget(minSize)` defaults to
it, so a control that writes `Modifier.touchTarget()` is already using the
shared minimum — the default argument *is* the token.

The task list carries an alias of that token, `TaskListSizes.MinTouchTarget`,
in its own theme package. The alias is the same 48 dp, and a test pins the two
together, so the numbers cannot drift apart. But the alias is a name that
belongs to one feature: a file outside the task list that sizes a touch target
with it has reached into another feature's theme package for a number that
names the whole app.

So the rule is one sentence: **outside the task list, a touch target is
expanded with the shared token — `TouchTarget.MinSize`, or the helper's own
default — and with nothing else.** A literal (`48.dp`) is the same number
written down twice, and the second copy is the one that drifts.

The same rule has a second half: a file outside the task list that imports the
task list's theme package *in order to size a touch target* has done the same
thing one step earlier, and the import is where the reach is visible. The
finding names the import, because the import is what a reader has to remove.

## What this gate reads

The gate reads **production sources**. A target in a test never reaches a
finger, and the repository's own measurement test expands a target with an
explicit size on purpose — that is the shape of a test, not a defect. Test
source sets are told apart by their **position in the path**
(`src/<source set>/`): a source set called `test`, and every source set whose
name ends in `Test` — `jvmTest`, `commonTest`, `androidHostTest`,
`androidTest`. The exclusion is deliberately not a path component called
`test`: `shared/src/commonMain/kotlin/.../test/` is production code in a
package that happens to be called `test`.

**The task-list feature's own files are exempt.** A file under
`feature/tasks/` may use the alias and the helper's default; the alias exists
for the task list's own controls, and they keep their call sites.

**Comments are not code.** The helper's own KDoc writes
`Modifier.touchTarget(minSize)` as an example, and a gate that read it would
report the file that defines the rule. The gate strips comments before it
reads anything.

## What this gate cannot see

* The value a constant carries. `touchTarget(MIN_SIZE)` is read as the
  argument it is given — the gate names the constant and asks for the token,
  because the constant's value is written somewhere the gate does not read.
* The declaration of the helper itself, `fun Modifier.touchTarget(minSize: Dp
  = TouchTarget.MinSize)`: it is a declaration, not a call site.
* A size written through a fully qualified name without an import
  (`com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes.…`):
  the import rule reads the import, and there is none to read.

Usage: python3 scripts/check-touch-target-token.py [--json] [--quiet] [--root DIR]

`--root` names the tree to check, so a control can point the gate at a
fixture instead of at the working tree.

Exit codes:
  0 = every touch target outside the task list is expanded with the shared token
  1 = a touch target is expanded with something else
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

#: The shared minimum. The only value a touch target may be expanded with
#: outside the task list, and the value the helper's default argument carries.
SHARED_TOKEN = "TouchTarget.MinSize"

#: The task list's theme package. Its sizes do not size a touch target
#: outside the task list.
TASK_LIST_THEME_PACKAGE = "com.singularity.todo.feature.tasks.presentation.theme"

#: The symbols of that package that carry a size. The colours and the theme
#: are not sizes, and moving the colours out of the package is not this
#: gate's business.
TASK_LIST_SIZE_SYMBOLS = frozenset({"TaskListSizes"})

#: The size-shaped calls. A size token that reaches one of them is a size
#: written into a control's geometry.
SIZE_CALLS = (
    "size",
    "sizeIn",
    "width",
    "height",
    "requiredSize",
    "requiredWidth",
    "requiredHeight",
    "defaultMinSize",
    "defaultWidth",
    "defaultHeight",
    "touchTarget",
    "minimumInteractiveComponentSize",
)

#: The expansion. The helper of the shared core UI package.
EXPANSION_RE = re.compile(r"\.\s*touchTarget\s*\(")

#: The import of the task list's theme package, with the symbol it names.
TASK_LIST_IMPORT_RE = re.compile(
    r"^[ \t]*import[ \t]+"
    + re.escape(TASK_LIST_THEME_PACKAGE)
    + r"\.(\w+)\b",
    re.MULTILINE,
)

#: A size-shaped call.
SIZE_CALL_RE = re.compile(r"\.\s*(?:" + "|".join(SIZE_CALLS) + r")\s*\(")

#: Build outputs: nothing in them ships to a device.
SKIPPED_DIRS = frozenset({"build"})


def _is_test_source(parts: tuple[str, ...]) -> bool:
    """Whether a path belongs to a test source set.

    The set is named by its position in the path (`src/<source set>/`), NOT by
    any path component called "test" — `shared/src/commonMain/kotlin/.../test/`
    is production code in a package that happens to be called "test", and
    skipping it would be the same blindness the chain gate was fixed for.
    """
    if "src" not in parts:
        return False
    index = parts.index("src")
    if index + 1 >= len(parts):
        return False
    source_set = parts[index + 1]
    return source_set == "test" or source_set.endswith("Test")


def _is_task_list(relative: pathlib.PurePath) -> bool:
    """Whether a file belongs to the task-list feature.

    The alias and the task list's controls live under `feature/tasks/`, in
    every module that has them. Those files are the ones the alias is for.
    """
    parts = relative.as_posix().split("/")
    return "feature" in parts and "tasks" in parts[parts.index("feature") + 1 :]


def _strip_comments(text: str) -> str:
    """The text of a file with its comments blanked out.

    The replacement keeps the length and the line structure of the original,
    so a line number read from the stripped text is the line number of the
    source. Strings and character literals are read as themselves, so a `//`
    inside a string is not a comment.
    """
    out = list(text)
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == "/" and index + 1 < length and text[index + 1] == "/":
            end = text.find("\n", index)
            end = length if end == -1 else end
            for position in range(index, end):
                out[position] = " "
            index = end
        elif char == "/" and index + 1 < length and text[index + 1] == "*":
            end = text.find("*/", index + 2)
            end = length if end == -1 else end + 2
            for position in range(index, end):
                if out[position] != "\n":
                    out[position] = " "
            index = end
        elif char == '"':
            quote = '"""' if text.startswith('"""', index) else '"'
            index += len(quote)
            while index < length:
                if text[index] == "\\":
                    index += 2
                    continue
                if text.startswith(quote, index):
                    index += len(quote)
                    break
                index += 1
        elif char == "'":
            index += 1
            while index < length:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == "'":
                    index += 1
                    break
                index += 1
        else:
            index += 1
    return "".join(out)


def _argument(text: str, open_paren: int) -> str:
    """The argument of a call whose opening parenthesis is at `open_paren`.

    The text between the parentheses, with the nesting counted, so an
    argument that carries a call of its own is read whole.
    """
    depth = 0
    index = open_paren
    length = len(text)
    while index < length:
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : index]
        index += 1
    return text[open_paren + 1 :]


def _is_declaration(text: str, match: re.Match[str]) -> bool:
    """Whether a match is the declaration of the helper, not a call of it.

    `fun Modifier.touchTarget(minSize: Dp = TouchTarget.MinSize)` declares
    the helper; `Modifier.touchTarget()` calls it. The declaration names the
    parameter, and a parameter is not a size a call site chose.
    """
    before = text[: match.start()]
    return re.search(r"\bfun\s+\w*\s*$", before) is not None


def _normalise(argument: str) -> str:
    """The argument of an expansion, as the value it names.

    A named argument (`minSize = TouchTarget.MinSize`) names the same value as
    a positional one, and the whitespace around it is not part of it.
    """
    value = argument.strip()
    value = re.sub(r"^minSize\s*=\s*", "", value)
    return re.sub(r"\s+", "", value)


def _collapse(argument: str) -> str:
    """The argument as one line, for a report that is one line.

    An argument that is written over several lines — a call the codebase
    formats across lines — must not break the report into several.
    """
    return re.sub(r"\s+", " ", argument).strip()


def _expansions(text: str) -> list[tuple[int, str]]:
    """The expansions of a file, as (offset, the argument it was given).

    A call with no argument is the helper's default, which *is* the shared
    token; it is reported here with an empty argument, and the caller reads
    the empty argument as "the default".
    """
    found: list[tuple[int, str]] = []
    for match in EXPANSION_RE.finditer(text):
        if _is_declaration(text, match):
            continue
        open_paren = text.index("(", match.end() - 1)
        found.append((match.start(), _argument(text, open_paren)))
    return found


def _sizes_a_touch_target(text: str, symbol: str) -> bool:
    """Whether a symbol from the task list's theme package sizes a control.

    The symbol has to reach a size-shaped call: an import that is used for
    something else is not this gate's business, and the colours of the task
    list live in that package too.
    """
    for match in SIZE_CALL_RE.finditer(text):
        open_paren = text.index("(", match.end() - 1)
        if re.search(r"\b" + re.escape(symbol) + r"\b", _argument(text, open_paren)):
            return True
    return False


def scan(root: pathlib.Path) -> list[dict[str, object]]:
    """Every touch target expanded with something else, as findings."""
    findings: list[dict[str, object]] = []
    for path in sorted(root.rglob("*.kt")):
        relative = path.relative_to(root)
        if SKIPPED_DIRS.intersection(relative.parts):
            continue
        if _is_test_source(relative.parts):
            continue
        if _is_task_list(relative):
            continue
        try:
            source = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        text = _strip_comments(source)

        for offset, argument in _expansions(text):
            value = _normalise(argument)
            if not value or value == SHARED_TOKEN:
                continue
            findings.append(
                {
                    "file": relative.as_posix(),
                    "line": text[:offset].count("\n") + 1,
                    "detail": (
                        f"the touch target is expanded with `{_collapse(argument)}`, and "
                        f"outside the task list the only value it may be expanded "
                        f"with is `{SHARED_TOKEN}` (or the helper's own default)"
                    ),
                }
            )

        for match in TASK_LIST_IMPORT_RE.finditer(text):
            symbol = match.group(1)
            if symbol not in TASK_LIST_SIZE_SYMBOLS:
                continue
            if not _sizes_a_touch_target(text, symbol):
                continue
            findings.append(
                {
                    "file": relative.as_posix(),
                    "line": text[: match.start()].count("\n") + 1,
                    "detail": (
                        f"`{symbol}` is imported from the task list's theme package "
                        f"to size a touch target; outside the task list the minimum "
                        f"comes from `{SHARED_TOKEN}` in the core UI package"
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
    """Every touch target outside the task list is expanded with the shared token."""
    tree = (root or ROOT).resolve()
    if not tree.is_dir():
        click.echo(f"check-touch-target-token.py: {tree} is not a directory", err=True)
        raise ctx.exit(IO_ERROR)

    findings = scan(tree)

    if emit_json:
        click.echo(json.dumps({"passed": not findings, "findings": findings}, indent=2))
    elif findings:
        click.echo(
            f"check-touch-target-token.py: {len(findings)} touch target(s) expanded "
            f"with something other than {SHARED_TOKEN}"
        )
        for finding in findings:
            click.echo(f"  {finding['file']}:{finding['line']}: {finding['detail']}")
    elif not quiet:
        click.echo(
            f"check-touch-target-token.py: every touch target outside the task list "
            f"is expanded with {SHARED_TOKEN}"
        )

    raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
