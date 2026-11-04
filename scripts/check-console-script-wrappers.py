#!/usr/bin/env python3
"""check-console-script-wrappers.py — every console script is bound to this checkout.

## What went wrong

``scripts/src/pyproject.toml`` declares the console scripts of the editable
install: ``sgate``, ``ensure-emulator`` and ``run-maestro``. An editable
install belongs to whichever checkout ran ``pip install -e scripts/src/`` last,
so a bare command on ``PATH`` answers with *that* checkout's package — it reads
that tree's gate registry, runs that tree's Maestro flows and installs that
tree's APK, and the report comes back green about code nobody is looking at.

``./sg`` and ``./rm`` exist for exactly that reason. Each loads
``scripts/package-binding.py`` by path — the package may be the thing that is
broken, so the fix cannot live inside it — and calls ``bind_local_package``
before its entry point runs.

## The rule

Nothing stated that rule, though. The three scripts were covered because two
wrappers happened to exist, and a fourth script added to the install would be
exposed exactly as ``run-maestro`` was: silently, by the environment. So:

**every name in ``[project.scripts]`` is reachable through a wrapper that loads
its own checkout's binding module by path and binds the packages before the
script's entry point runs.**

Recognition is by shape, not by file name, and every fact is read where a fact
can live — never in prose:

  - a *wrapper* is a root-level file whose first line is a shebang. The
    executable bit is not consulted: ``os.access(X_OK)`` answers for the mount
    as well as for the checkout, so a ``noexec`` mount would read as a tree
    with no wrappers at all, and a wrapper that lost its bit is still a wrapper.
    Whether the bit is set is git's question;
  - the *entry point* is ``from <module> import <attribute>`` — or ``import
    <module>`` and a later ``<module>.<attribute>`` — and comments and string
    literals are blanked before that search, so a docstring that names the
    import has run nothing;
  - the *binding call* is an actual ``bind_local_package(...)`` statement, read
    the same way: a comment naming the call is not a call, which is what keeps
    the registry's sabotage control honest once a wrapper's header starts
    discussing it;
  - the binding module is loaded by a *relative* path that the wrapper builds
    from a variable derived from its own location (``BASH_SOURCE`` in bash,
    ``__file__`` in Python). A fixed ``/other-clone/scripts/package-binding.py``
    binds that tree, however correctly it binds it — the exact failure above;
  - coverage is per *script*: two entry points that share a module are two
    claims, and a wrapper that reaches ``main`` has not reached ``other``.

A wrapper that imports the entry point first and binds afterwards is not
accepted either — by then the package has already answered from wherever the
environment points.

An install that declares no console scripts is a finding too: a check that
reads an empty declaration is green about nothing.

Usage:
    python3 scripts/check-console-script-wrappers.py
    python3 scripts/check-console-script-wrappers.py --quiet
    python3 scripts/check-console-script-wrappers.py --json

Exit 0 when every declared script has a bound wrapper. Exit 1 when one does
not. Exit 2 when the declaration cannot be read, or a root-level file cannot be
read at all: an unreadable wrapper is an input error, not a missing wrapper.
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import sys
import tomllib

import click

# This script must resolve `sgate` against *this* checkout: an editable install
# of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The one list of console scripts: the install's declaration.
DECLARATION = pathlib.Path("scripts") / "src" / "pyproject.toml"

#: The module a wrapper loads by path. The binding cannot be imported: the
#: package may be missing or foreign, which is the case it exists for.
BINDING_MODULE = "package-binding.py"

#: How the binding is loaded. A file that mentions the module but never loads
#: it by path has bound nothing.
LOAD_CALL = "spec_from_file_location"

#: The call that binds the packages. It has to come before the entry point.
BIND_CALL = "bind_local_package("

#: A wrapper is a script: its first line names its interpreter. Recognition is
#: by content, because a mode bit is a property of the checkout and of the
#: mount, not of the file.
SHEBANG = "#!"

#: The two ways a script can name the directory it lives in: bash's
#: ``BASH_SOURCE`` and Python's ``__file__``.
SELF_LOCATION = ("__file__", "BASH_SOURCE")

#: A console-script declaration: ``module:attribute``.
ENTRY_POINT_RE = re.compile(r"^(?P<module>[\w.]+):(?P<attribute>[\w.]+)$")

#: A statement-position import, so that the name has to be one.
FROM_IMPORT_RE = re.compile(r"(?m)^[ \t]*from[ \t]+(?P<module>[\w.]+)[ \t]+import\b")
IMPORT_RE = re.compile(r"(?m)^[ \t]*import\b")

#: A statement-position call, dotted receiver or not, so that a name in the
#: middle of an expression is not mistaken for the call.
BIND_RE = re.compile(r"(?m)^[ \t]*(?:[\w]+\.)*bind_local_package[ \t]*\(")

#: The assignment half of ``root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"``
#: and of ``root = pathlib.Path(__file__).parent``.
ASSIGNMENT_RE = re.compile(r"^(?:(?:export|local|declare|readonly)[ \t]+)?(?P<name>[A-Za-z_]\w*)[ \t]*=")


class DeclarationError(RuntimeError):
    """The install's console-script declaration could not be read as one."""


def console_scripts(declaration: pathlib.Path) -> dict[str, str]:
    """The ``[project.scripts]`` table of the install.

    @param declaration the pyproject.toml that declares the install.
    @return console script name → ``module:attribute`` entry point.
    """
    data = tomllib.loads(declaration.read_text(encoding="utf-8"))
    project = data.get("project")
    if not isinstance(project, dict):
        raise DeclarationError("the file has no [project] table")
    scripts = project.get("scripts")
    if not isinstance(scripts, dict):
        raise DeclarationError("the file has no [project.scripts] table")
    return {str(name): str(target) for name, target in scripts.items()}


def blank(text: str) -> tuple[str, str, list[tuple[int, int]]]:
    """The source three ways: without comments, without strings, and its literals.

    The two views answer different questions. ``without_comments`` keeps string
    literals, because the path to the binding module *is* a string and blanking
    it would hide the one fact that has to be read. ``code`` blanks comments
    and string literals both, because an import statement and a call are code:
    a comment or a docstring that names either has executed nothing, and
    letting prose stand in for the statement would make the gate report a
    wrapper that binds when no wrapper binds.

    Both views are the same length as ``text`` — noise becomes spaces and
    newlines survive — so an offset found in one is an offset in the others.

    @param text the script as written.
    @return
        the two views, and the ``(start, end)`` content spans of its literals.
    """
    without_comments = list(text)
    code = list(text)
    literals: list[tuple[int, int]] = []

    index = 0
    length = len(text)
    while index < length:
        character = text[index]
        if character == "#":
            end = text.find("\n", index)
            end = length if end == -1 else end
            for position in range(index, end):
                without_comments[position] = " "
                code[position] = " "
            index = end
            continue
        if character in "\"'":
            quote = character * 3 if text.startswith(character * 3, index) else character
            end = index + len(quote)
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text.startswith(quote, end):
                    end += len(quote)
                    break
                end += 1
            content = index + len(quote)
            literals.append((content, max(content, min(end, length) - len(quote))))
            for position in range(index, min(end, length)):
                code[position] = "\n" if text[position] == "\n" else " "
            index = end
            continue
        index += 1

    return "".join(without_comments), "".join(code), literals


def statement(code: str, start: int) -> str:
    """The statement beginning at ``start``, brackets and continuations included.

    @param code the source with comments and string literals blanked.
    @param start the offset the statement starts at.
    @return the statement's text, to its end of line or its closing bracket.
    """
    depth = 0
    index = start
    length = len(code)
    while index < length:
        character = code[index]
        if character == "\\":
            index += 2
            continue
        if character in "([{":
            depth += 1
        elif character in ")]}":
            depth = max(0, depth - 1)
            if depth == 0:
                end = code.find("\n", index)
                return code[start : length if end == -1 else end]
        elif character == "\n" and depth == 0:
            return code[start:index]
        index += 1
    return code[start:]


def imported_names(text: str) -> set[str]:
    """The names an ``import`` list names, ``as`` aliases reduced to the name.

    @param text a statement, from ``import`` onwards.
    @return the imported names.
    """
    _, _, names = text.partition("import")
    cleaned = names.replace("(", " ").replace(")", " ").replace("\\", " ")
    return {
        name
        for item in cleaned.split(",")
        if (name := item.split(" as ")[0].strip())
    }


class Wrapper:
    """A root-level script, read as a candidate wrapper.

    One read, three views, so that every question below is asked of the part of
    the file where the answer can live.
    """

    def __init__(self, path: pathlib.Path, text: str) -> None:
        """@param path where the script is. @param text the script as written."""
        self.path = path
        self.text = text
        self.without_comments, self.code, self.literals = blank(text)
        call = BIND_RE.search(self.code)
        self.bind_offset = call.start() if call is not None else -1

    def own_directory_names(self) -> set[str]:
        """The variables this wrapper derives from its own location.

        A wrapper names the directory it lives in exactly once —
        ``root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`` in bash,
        ``root = pathlib.Path(__file__).parent`` in Python — and builds
        everything it loads from that variable. Requiring the binding path to be
        built from such a variable is what ties the load to *this* checkout
        instead of to a path typed into the file.

        @return the names assigned on a line that names the script's own path.
        """
        names: set[str] = set()
        for line in self.without_comments.splitlines():
            if not any(anchor in line for anchor in SELF_LOCATION):
                continue
            assignment = ASSIGNMENT_RE.match(line.strip())
            if assignment is not None:
                names.add(assignment.group("name"))
        return names

    def binding_module_literal(self) -> tuple[str, int] | None:
        """The path literal handed to the loader, if it names the binding module.

        @return the literal's value and where it starts, or ``None``.
        """
        for start, end in self.literals:
            value = self.text[start:end]
            if value.rsplit("/", 1)[-1] == BINDING_MODULE:
                return value, start
        return None

    def loads_own_binding(self) -> bool:
        """Whether the wrapper loads *its own* checkout's binding module by path.

        Three facts: the loader is called, the module is named by a path, and
        that path is relative and built from a variable derived from the
        script's own location. The last one is the point — a wrapper that loads
        a fixed ``/other-clone/scripts/package-binding.py`` binds that tree, and
        binding it correctly is exactly the green report about somebody else's
        checkout this gate exists to refuse.

        @return whether the load is this checkout's.
        """
        load = self.code.find(LOAD_CALL)
        if load == -1:
            return False
        found = self.binding_module_literal()
        if found is None:
            return False
        path, literal = found
        if os.path.isabs(path) or path.startswith("~") or ".." in pathlib.PurePath(path).parts:
            return False

        # The load and the path it is given are one statement, however the
        # wrapper splits it across lines. The path is built from a variable the
        # wrapper derived from its own location, or it is a path from nowhere.
        first, last = sorted((load, literal))
        region_start = self.without_comments.rfind("\n", 0, first) + 1
        region_end = self.without_comments.find("\n", last)
        region = self.without_comments[
            region_start : len(self.without_comments) if region_end == -1 else region_end
        ]
        return any(name in region for name in self.own_directory_names())

    def entry_point_offset(self, module: str, attribute: str) -> int:
        """Where the wrapper first reaches ``module``'s ``attribute``, or -1.

        Two shapes count, and only as code: ``from <module> import <attribute>``
        — including the parenthesised and continued forms — and ``import
        <module>`` followed by a use of ``<module>.<attribute>``. A comment, a
        docstring or a prompt string that names either has run nothing.

        @param module the entry-point module of the console script.
        @param attribute the entry-point attribute of the console script.
        @return the offset of the first reach, or -1.
        """
        offsets: list[int] = [
            match.start()
            for match in FROM_IMPORT_RE.finditer(self.code)
            if match.group("module") == module
            and attribute in imported_names(statement(self.code, match.end("module")))
        ]

        if any(
            module in imported_names(statement(self.code, match.start()))
            for match in IMPORT_RE.finditer(self.code)
        ):
            use = re.search(rf"\b{re.escape(module)}\.{re.escape(attribute)}\b", self.code)
            if use is not None:
                offsets.append(use.start())

        return min(offsets) if offsets else -1

    def covers(self, module: str, attribute: str) -> bool:
        """Whether the wrapper binds this checkout before it reaches the entry point.

        The ordering is the part a name match cannot give: a wrapper that
        imports the entry point first has already resolved the package from the
        environment, and binding afterwards fixes nothing.

        @param module the entry-point module of the console script.
        @param attribute the entry-point attribute of the console script.
        @return whether the wrapper covers the script.
        """
        if not self.loads_own_binding():
            return False
        entry = self.entry_point_offset(module, attribute)
        return self.bind_offset != -1 and entry != -1 and self.bind_offset < entry


def wrapper_files(root: pathlib.Path) -> list[pathlib.Path]:
    """The files at the repository root a wrapper can be.

    A wrapper is a root-level script — that is where ``./sg`` and ``./rm``
    live. Nothing here consults the executable bit: ``os.access(X_OK)`` reports
    the mount's policy as well as the checkout's mode, so on a ``noexec`` mount
    every wrapper would read as absent and the finding would blame the tree for
    a property of where it is mounted. Whether the bit is set is git's
    question; this gate asks whether a wrapper exists and whether it binds, so
    a script that lost its bit is still found. Recognition is by content (see
    :data:`SHEBANG`), so a file that happens to sit here and binds nothing is
    simply a file the check does not count.

    @param root the repository root.
    @return the root-level regular files, sorted.
    """
    return sorted(path for path in root.iterdir() if path.is_file())


def read_wrappers(root: pathlib.Path) -> list[Wrapper]:
    """The root-level scripts, read once each.

    An unreadable root-level file is *not* skipped: skipping it would report a
    wrapper nobody can read as a wrapper that is not there, which is a different
    claim and a wrong one. The error reaches the CLI, which answers exit 2.

    @param root the repository root.
    @return the scripts whose first line names an interpreter.
    """
    wrappers: list[Wrapper] = []
    for path in wrapper_files(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        if text.startswith(SHEBANG):
            wrappers.append(Wrapper(path, text))
    return wrappers


def scan(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """The declared console scripts and the ones with no checkout-bound wrapper.

    @param root the repository root.
    @return the declaration, and one finding per uncovered script.
    """
    scripts = console_scripts(root / DECLARATION)
    wrappers = read_wrappers(root)

    findings: list[str] = []
    if not scripts:
        findings.append(
            f"{DECLARATION}: the install declares no console scripts — "
            "a check that reads an empty declaration proves nothing"
        )

    for name, target in sorted(scripts.items()):
        match = ENTRY_POINT_RE.match(target)
        if match is None:
            findings.append(
                f"{name}: {target!r} is not a module:attribute entry point, "
                "so nothing can tell which module the wrapper has to bind before"
            )
            continue
        module = match.group("module")
        attribute = match.group("attribute")
        if any(wrapper.covers(module, attribute) for wrapper in wrappers):
            continue
        findings.append(
            f"{name}: {target} has no checkout-bound wrapper at the repository root "
            f"— a wrapper has to load {BINDING_MODULE} by a relative path built from "
            f"its own directory, call {BIND_CALL.rstrip('(')} before the entry point "
            f"runs, and then reach {module}:{attribute} (from {module} import "
            f"{attribute}, or import {module} and a use of {module}.{attribute})"
        )

    return scripts, findings


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.option("--quiet", is_flag=True, help="Print nothing when every script is covered.")
@click.option(
    "--root",
    type=click.Path(file_okay=False, path_type=pathlib.Path),
    default=None,
    help="The tree to check (default: the repository this script lives in).",
)
@click.pass_context
def cli(
    ctx: click.Context,
    emit_json: bool,
    quiet: bool,
    root: pathlib.Path | None,
) -> None:
    """Every console script the install declares has a wrapper for this checkout."""
    tree = (root or ROOT).resolve()
    declaration = tree / DECLARATION
    if not declaration.is_file():
        click.echo(f"check-console-script-wrappers.py: {declaration} is missing", err=True)
        raise ctx.exit(IO_ERROR)

    try:
        scripts, findings = scan(tree)
    except (tomllib.TOMLDecodeError, DeclarationError) as exc:
        click.echo(f"check-console-script-wrappers.py: cannot read {declaration}: {exc}", err=True)
        raise ctx.exit(IO_ERROR)
    except OSError as exc:
        # A root-level file nobody can read is an input error, and the message
        # has to name *that* file: blaming the declaration would send the reader
        # to a file that is fine.
        unreadable = exc.filename or str(tree)
        click.echo(f"check-console-script-wrappers.py: cannot read {unreadable}: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "passed": not findings,
                    "console_scripts": sorted(scripts),
                    "findings": findings,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    elif findings:
        click.echo(
            "check-console-script-wrappers.py: "
            f"{len(findings)} console script(s) with no checkout-bound wrapper"
        )
        for finding in findings:
            click.echo(f"  {finding}")
    elif not quiet:
        click.echo(
            "check-console-script-wrappers.py: "
            f"{', '.join(sorted(scripts))} all bind this checkout"
        )

    raise ctx.exit(CLEAN if not findings else VALIDATION_FAILURE)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
