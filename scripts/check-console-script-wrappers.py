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
the binding module by path and binds the packages before the script's
entry-point module runs.**

Recognition is by shape, not by file name: a wrapper that binds and then
reaches ``emulator._cli`` is what covers ``ensure-emulator``, whatever the
wrapper is called. A wrapper that imports the entry point first and binds
afterwards is not accepted — by then the package has already answered from
wherever the environment points.

An install that declares no console scripts is a finding too: a check that
reads an empty declaration is green about nothing.

Usage:
    python3 scripts/check-console-script-wrappers.py
    python3 scripts/check-console-script-wrappers.py --quiet
    python3 scripts/check-console-script-wrappers.py --json

Exit 0 when every declared script has a bound wrapper. Exit 1 when one does
not. Exit 2 when the declaration cannot be read.
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

#: A console-script declaration: ``module:attribute``.
ENTRY_POINT_RE = re.compile(r"^(?P<module>[\w.]+):(?P<attribute>[\w.]+)$")


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


def wrapper_files(root: pathlib.Path) -> list[pathlib.Path]:
    """The files at the repository root a wrapper can be.

    A wrapper is a root-level executable — that is where ``./sg`` and ``./rm``
    live. Recognition is by content, so a file that happens to sit here and
    binds nothing is simply a file the check does not count.

    @param root the repository root.
    @return the executable regular files at the root, sorted.
    """
    return sorted(
        path
        for path in root.iterdir()
        if path.is_file() and os.access(path, os.X_OK)
    )


def entry_point_position(text: str, module: str) -> int:
    """Where a wrapper first *runs* the entry point, or -1.

    An import statement, not a mention: a comment or a docstring that names the
    module has not run it, and treating the name as the run would let prose
    stand in for the import the wrapper has to place after the binding.

    @param text the wrapper's source.
    @param module the entry-point module of the console script.
    @return the offset of the first import of ``module``, or -1.
    """
    positions = [
        position
        for position in (text.find(f"from {module} import"), text.find(f"import {module}"))
        if position != -1
    ]
    return min(positions) if positions else -1


def binds_before(text: str, module: str) -> bool:
    """Whether a wrapper binds the packages before ``module`` runs.

    Three facts, in order: the file loads the binding by path, it calls the
    binding, and that call is written before the entry point is imported. The
    ordering is the part a name match cannot give: a wrapper that imports the
    entry point first has already resolved the package from the environment.

    @param text the wrapper's source.
    @param module the entry-point module of the console script.
    @return whether the wrapper covers the script.
    """
    if BINDING_MODULE not in text or LOAD_CALL not in text:
        return False
    bind = text.find(BIND_CALL)
    entry = entry_point_position(text, module)
    return bind != -1 and entry != -1 and bind < entry


def scan(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """The declared console scripts and the ones with no checkout-bound wrapper.

    @param root the repository root.
    @return the declaration, and one finding per uncovered script.
    """
    scripts = console_scripts(root / DECLARATION)

    wrappers: list[str] = []
    for path in wrapper_files(root):
        try:
            wrappers.append(path.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            continue

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
        if any(binds_before(text, module) for text in wrappers):
            continue
        findings.append(
            f"{name}: {target} has no checkout-bound wrapper at the repository root "
            f"— a wrapper has to load {BINDING_MODULE} by path and call "
            f"{BIND_CALL.rstrip('(')} before {module} runs"
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
    except (OSError, tomllib.TOMLDecodeError, DeclarationError) as exc:
        click.echo(f"check-console-script-wrappers.py: cannot read {declaration}: {exc}", err=True)
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
