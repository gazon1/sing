#!/usr/bin/env python3
"""check-script-conventions.py — enforce naming, docstring, and import conventions for gate scripts.

What this gate enforces (2026-10-20, audited):
  R5.1: Gate script names match `check-<gate-name>.py` (kebab-case)
  R5.2: Shell wrappers that only exec/python3 are deleted (no pass-through wrappers)
  R2.1: Gate scripts use Click, not argparse
  R2.4: A gate script locates the repository itself, never through the
        installed package (R2.4-R2.6, added 2026-10-22)
  R2.7: A gate script routes its exit code through the shared runner,
        `sgate.commands.base.run` — or declares, in a comment, why it cannot
        (added 2026-10-23)

What it declares but does not enforce:
  R5.3: All imports use absolute imports (no relative `from .foo import`)
  R5.4: Gate scripts that implement a decision reference the ADR in a comment
  R2.2: Every gate command accepts --check and --json flags
  R3.x: the output/exit-code conventions (R3.1-R3.7)

Why the list is short
---------------------
Until 2026-10-20 this file advertised eight rules and enforced two, which is
the failure mode this gate exists to catch elsewhere:

  - R5.4 could never fire. Its condition was
    `if "ADR" not in content and "#" not in content.split("\\n")[0]` — the
    first line of a script is its shebang, which always contains `#`, so the
    second half was always true and the rule was never reached. Fixing the
    condition exposes 26 scripts that cite no ADR, so making R5.4 real is a
    change of its own (it needs those 26 scripts to grow a reference) and not
    a bug fix that belongs in a refactoring commit.

  - `check_json_flag` was a no-op: every branch continued or fell off the end
    of the loop, so its error list was always empty. It claimed to check
    R2.2 and R3.7, and it is not wired to any of them.

Both were removed rather than repaired, because repairing them is *adding*
lint coverage — a separate decision with its own cost, not a simplification.
The declared rules stay listed above so that the gap is visible rather than
invisible: a rule nobody enforces is a claim the repository does not make.

Why R2.1 is detected with the AST and not with a substring search
------------------------------------------------------------------
The rule used to be `"import argparse" in content`. That is a mention, not
an import: this file contains the literal text in the docstring above and
in the rule it documents, so the gate flagged *itself* on every run, next
to the two scripts that genuinely used argparse. A rule that cannot tell a
mention from an import cannot be applied to the file that documents it, and
a gate that always reports itself is a gate nobody reads past its first
line. The detector below therefore walks the parse tree and looks at real
import statements only.

A file that does not parse is not silently clean either: a gate script that
cannot be parsed cannot be shown to obey the rule, so it is reported as a
violation carrying the parser's own message.
"""
from __future__ import annotations

import ast
import io
import json
import os
import pathlib
import re
import tokenize
from pathlib import Path

import click

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = Path(__file__).resolve().parent.parent


def check_naming_and_structure(scripts_dir: Path) -> list[str]:
    """Check R5 naming conventions."""
    errors = []
    for path in sorted(scripts_dir.glob("check-*.py")):
        name = path.name

        # R5.1: must match check-<name>.py
        if not re.match(r"^check-[a-z0-9][a-z0-9-]*\.py$", name):
            errors.append(f"{name}: does not match check-<name>.py kebab-case pattern")

        # R5.2: no shell wrappers that only exec python3
        shell_path = path.with_suffix(".sh")
        if shell_path.exists():
            content = shell_path.read_text()
            lines = [l.strip() for l in content.splitlines() if l.strip() and not l.strip().startswith("#")]
            if len(lines) == 1 and ("exec python3" in lines[0] or "exec bash" in lines[0]):
                errors.append(
                    f"{shell_path.name}: thin shell wrapper (only exec) — "
                    "call the Python script directly instead"
                )

        # R5.4 (an ADR reference in the header comment) was removed on
        # 2026-10-20: its condition could never be true, and making it real
        # would expose 26 scripts that cite no ADR — a lint-coverage change,
        # not a simplification. See the module docstring.

    return errors


def find_argparse_import(source: str) -> tuple[int | None, str | None]:
    """
    The line of the first real `argparse` import, and a parse error.

    Returns `(lineno, None)` when the source imports argparse, `(None, None)`
    when it does not, and `(None, message)` when the source does not parse.

    A real import is `import argparse`, `import argparse as ap`,
    `import argparse.<submodule>`, `from argparse import ...` or
    `from argparse.<submodule> import ...`. The word appearing in a string,
    a comment or a docstring is a mention of the rule, not a use of it.
    """
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return None, f"does not parse as Python ({exc.msg} at line {exc.lineno})"
    except ValueError as exc:  # null bytes and other unparsable sources
        return None, f"does not parse as Python ({exc})"

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == "argparse" or alias.name.startswith("argparse."):
                    return node.lineno, None
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if module == "argparse" or module.startswith("argparse."):
                return node.lineno, None
    return None, None


def check_cli_framework(scripts_dir: Path) -> list[str]:
    """Check R2.1: gate scripts use Click, not argparse."""
    errors = []
    for path in sorted(scripts_dir.glob("check-*.py")):
        source = path.read_text(encoding="utf-8", errors="replace")
        lineno, parse_error = find_argparse_import(source)
        if parse_error is not None:
            errors.append(f"{path.name}: {parse_error} — cannot check its CLI framework")
            continue
        if lineno is not None:
            errors.append(f"{path.name}:{lineno}: uses argparse — must use Click (R2.1)")
    return errors


def check_json_flag(scripts_dir: Path) -> list[str]:
    """R2.2/R3.7 — every gate command accepts --check and --json.

    Not enforced. The function used to be a no-op: every branch continued or
    fell off the end of the loop, so it returned an empty list while claiming
    to check two rules. Enforcing it for real is a lint-coverage change (28
    of 35 scripts lack --check, 24 lack --json), so it is not decided here.

    Kept as a named function with an empty body rather than deleted, so that
    the gap is a visible line in the file rather than a rule that quietly
    disappeared between two versions.
    """
    return []


#: The modules of the `sgate` package that carry facts about *this* checkout:
#: the manifest of the gates, the registry, the shared vocabulary of a gate.
#: Read from an installed package, they describe a different tree — a sibling
#: worktree on the same machine, installed as an editable package under the
#: same name.
CHECKOUT_SPECIFIC_MODULES = (
    "sgate.commands._registry",
    "sgate.gate_controls",
    "sgate.gate_manifest",
    "sgate.gatekit",
    "sgate.gates",
    "sgate.repo",
)

def _is_bootstrap(statement: ast.stmt, source: str) -> bool:
    """Whether a statement puts the package root on `sys.path`.

    The package root is the directory named `src` — `scripts/src`, the
    directory that holds the `sgate` package. A bootstrap of some other
    directory (`scripts/`, for a sibling module) is a different thing and does
    not count.

    A function or a class is not a bootstrap: the `sys.path.insert` inside it
    happens when it is called, which is after every module-level import has
    already been bound.
    """
    if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return False
    segment = ast.get_source_segment(source, statement)
    if segment is None:
        return False
    if "sys.path.insert" not in segment and "sys.path[:0]" not in segment:
        return False
    return "src" in segment.lower()


def _module_bindings(tree: ast.Module, source: str, before: ast.stmt) -> dict[str, str]:
    """The module-level assignments before a statement, as source expressions.

    A bootstrap usually names a variable rather than the path itself:
    `_SRC = pathlib.Path(__file__).resolve().parent / "src"` and then
    `sys.path.insert(0, str(_SRC))`. Reading the target means reading the
    assignment it names.
    """
    bindings: dict[str, str] = {}
    for statement in tree.body:
        if statement is before:
            break
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if not isinstance(target, ast.Name):
            continue
        expression = ast.get_source_segment(source, statement.value)
        if expression:
            bindings[target.id] = expression
    return bindings


def _bootstrap_target(
    statement: ast.stmt, source: str, script: Path, bindings: dict[str, str]
) -> Path | None:
    """The directory a bootstrap puts on `sys.path`, when it can be read.

    The expression is evaluated in a namespace with the script's own location
    — the same names the script has. A bootstrap that points at a directory
    that does not exist is worse than no bootstrap: it looks like the gate
    resolves this checkout's code, and it resolves somebody else's.
    """
    expression = None
    for node in ast.walk(statement):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
            continue
        if node.func.attr != "insert":
            continue
        target = node.func.value
        if (
            isinstance(target, ast.Attribute)
            and target.attr == "path"
            and isinstance(target.value, ast.Name)
            and target.value.id == "sys"
        ):
            expression = ast.get_source_segment(source, node.args[1]) if len(node.args) > 1 else None
            break
    if not expression:
        return None
    namespace: dict[str, object] = {
        "__file__": str(script),
        "pathlib": pathlib,
        "Path": pathlib.Path,
        "os": os,
        "str": str,
    }
    for name, bound in bindings.items():
        try:
            namespace[name] = eval(bound, {"__builtins__": {}}, namespace)  # noqa: S307
        except Exception:  # noqa: BLE001 — an expression we cannot read is not a finding
            continue
    try:
        value = eval(expression, {"__builtins__": {}}, namespace)  # noqa: S307
    except Exception:  # noqa: BLE001 — an expression we cannot read is not a finding
        return None
    try:
        return pathlib.Path(value)
    except TypeError:
        return None


def _imported_sgate_modules(statement: ast.stmt) -> list[str]:
    """The `sgate` modules an import statement names."""
    modules: list[str] = []
    if isinstance(statement, ast.ImportFrom):
        if statement.module and statement.module.split(".")[0] == "sgate":
            modules.append(statement.module)
    elif isinstance(statement, ast.Import):
        for alias in statement.names:
            if alias.name.split(".")[0] == "sgate":
                modules.append(alias.name)
    return modules


def _is_checkout_specific(module: str) -> bool:
    return any(
        module == name or module.startswith(name + ".") for name in CHECKOUT_SPECIFIC_MODULES
    )


def check_package_resolution(scripts_dir: Path) -> list[str]:
    """R2.4, R2.5, R2.6 — how a gate script resolves the package it imports.

    R2.4: a gate script takes the repository root — and any path into the
    repository — from its own location, never from the installed package. A
    gate that reads the installed package's root reads *another checkout's*
    root: on a machine with several clones, the installed `sgate` is whichever
    one was installed last. Such a gate certified a tree nobody was working
    on, and reported success.

    R2.5: a gate script that imports a module carrying this checkout's facts
    must have this checkout's package root on `sys.path` *before* that import.

    R2.6: a bootstrap that comes after the import it was meant to serve is a
    no-op for that import — the name was already bound. The rule is checked
    separately because the mistake is invisible: the script runs, and it runs
    against the wrong tree.
    """
    errors: list[str] = []
    paths = sorted(scripts_dir.glob("check-*.py")) + sorted((scripts_dir / "ci").glob("*.py"))
    for path in paths:
        source = path.read_text(encoding="utf-8", errors="replace")
        try:
            tree = ast.parse(source)
        except SyntaxError as exc:
            errors.append(f"{path.name}: does not parse as Python ({exc.msg}) — cannot check it")
            continue

        errors.extend(_check_root_from_package(path, tree))
        errors.extend(_check_bootstrap_order(path, tree, source))
        errors.extend(_check_bootstrap_target(path, tree, source))
    return errors


def _check_bootstrap_target(path: Path, tree: ast.Module, source: str) -> list[str]:
    """R2.5 — the bootstrap points at the directory that holds the package.

    A bootstrap that names a directory that does not exist resolves nothing:
    the import that follows falls through to whatever is installed. The gate
    still runs, and it runs against another checkout's code.
    """
    errors: list[str] = []
    for statement in tree.body:
        if not _is_bootstrap(statement, source):
            continue
        target = _bootstrap_target(statement, source, path, _module_bindings(tree, source, statement))
        if target is None:
            continue
        package = target / "sgate" / "__init__.py"
        if not package.is_file():
            errors.append(
                f"{path.name}:{statement.lineno}: the bootstrap points at {target}, "
                "which does not hold the `sgate` package (R2.5) — the import that "
                "follows falls through to the installed package"
            )
    return errors


def _check_root_from_package(path: Path, tree: ast.Module) -> list[str]:
    """R2.4 — the repository is located by the script, not by the package."""
    errors: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module == "sgate.repo":
            names = ", ".join(alias.name for alias in node.names)
            errors.append(
                f"{path.name}:{node.lineno}: takes {names} from the installed package (R2.4) — "
                "the repository belongs to the script, not to whatever `sgate` is installed"
            )
        elif isinstance(node, ast.Attribute) and node.attr in ("ROOT", "SRC"):
            value = node.value
            if (
                isinstance(value, ast.Attribute)
                and value.attr == "repo"
                and isinstance(value.value, ast.Name)
                and value.value.id == "sgate"
            ):
                errors.append(
                    f"{path.name}:{node.lineno}: reads the repository root from the installed "
                    "package (R2.4) — locate the repository from the script's own location"
                )
    return errors


def _check_bootstrap_order(path: Path, tree: ast.Module, source: str) -> list[str]:
    """R2.5 and R2.6 — the bootstrap precedes the import it serves."""
    errors: list[str] = []
    module_bootstrap = None
    module_first_import = None
    for statement in tree.body:
        if _is_bootstrap(statement, source) and module_bootstrap is None:
            module_bootstrap = statement.lineno
        for module in _imported_sgate_modules(statement):
            if module_first_import is None:
                module_first_import = statement.lineno
            if _is_checkout_specific(module) and module_bootstrap is None:
                errors.append(
                    f"{path.name}:{statement.lineno}: imports `{module}` without this checkout's "
                    "package root on `sys.path` (R2.5)"
                )

    if module_bootstrap is not None and module_first_import is not None:
        if module_bootstrap > module_first_import:
            errors.append(
                f"{path.name}:{module_bootstrap}: the bootstrap of the package root comes after "
                f"the import at line {module_first_import} (R2.6) — an import that happens first "
                "is already bound to the installed package"
            )

    errors.extend(_check_scoped_imports(path, tree, source, module_bootstrap is not None))
    return errors


def _check_scoped_imports(
    path: Path, tree: ast.Module, source: str, module_has_bootstrap: bool
) -> list[str]:
    """R2.5 inside a function: the import and its bootstrap share a scope.

    A bootstrap at module level covers the imports inside the functions: the
    path is already on `sys.path` by the time the function runs.
    """
    errors: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        bootstrap = None
        for statement in node.body:
            if _is_bootstrap(statement, source) and bootstrap is None:
                bootstrap = statement.lineno
            for module in _imported_sgate_modules(statement):
                if _is_checkout_specific(module) and bootstrap is None and not module_has_bootstrap:
                    errors.append(
                        f"{path.name}:{statement.lineno}: imports `{module}` without this "
                        "checkout's package root on `sys.path` (R2.5)"
                    )
    return errors


#: The marker a gate script carries when it cannot use the shared runner.
#:
#: The runner is what maps a gate's own exit code onto the codes the gate
#: suite reads, and it is what a gate script ends with. A script that cannot
#: import it has to say so — and the reason is the point: "this script has to
#: run when the package is missing" is a decision, and a decision that is not
#: written down is a decision the next reader has to reverse-engineer.
NO_SGATE_MARKER = re.compile(r"^#\s*no-sgate:\s*(?P<reason>.*)$")


def _comments(source: str) -> list[str]:
    """The comments of a Python source, as the tokenizer sees them.

    A substring search would find the marker inside a docstring — and a
    docstring that *documents* the marker is not a script that *declares* it.
    """
    try:
        tokens = tokenize.generate_tokens(io.StringIO(source).readline)
        return [token.string for token in tokens if token.type == tokenize.COMMENT]
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return []


def _imports_the_runner(tree: ast.Module) -> bool:
    """Whether the source imports `sgate.commands.base`."""
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and (node.module or "") == "sgate.commands.base":
            return True
    return False


def check_sgate_runner(scripts_dir: Path) -> list[str]:
    """R2.7 — the shared runner, or a declared reason why not.

    The runner is one line at the end of a gate script, and it is what makes
    the script's exit code mean the same thing as every other gate's. A
    script that ends some other way is a gate whose verdict the suite reads
    differently — and a script that cannot use the runner at all has to say
    why, in a comment, so that the exception is a decision and not an
    oversight.
    """
    errors: list[str] = []
    for path in sorted(scripts_dir.glob("check-*.py")):
        source = path.read_text(encoding="utf-8", errors="replace")
        try:
            tree = ast.parse(source)
        except SyntaxError as exc:
            errors.append(f"{path.name}: does not parse as Python ({exc.msg}) — cannot check it")
            continue

        if _imports_the_runner(tree):
            continue

        markers = [
            match
            for comment in _comments(source)
            if (match := NO_SGATE_MARKER.match(comment.strip())) is not None
        ]
        if not markers:
            errors.append(
                f"{path.name}: does not route its exit code through "
                "`sgate.commands.base.run` (R2.7) — use the runner, or declare "
                "`# no-sgate: <why>` in a comment"
            )
            continue
        if not any(match.group("reason").strip() for match in markers):
            errors.append(
                f"{path.name}: declares `# no-sgate:` without a reason (R2.7) — the marker "
                "says why the script cannot use the runner"
            )
    return errors


@click.command()
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, check: bool, emit_json: bool) -> None:
    """Enforce the naming, docstring, and import conventions of gate scripts."""
    scripts_dir = ROOT / "scripts"
    all_errors: list[str] = []

    all_errors.extend(check_cli_framework(scripts_dir))
    all_errors.extend(check_naming_and_structure(scripts_dir))
    all_errors.extend(check_json_flag(scripts_dir))
    all_errors.extend(check_package_resolution(scripts_dir))
    all_errors.extend(check_sgate_runner(scripts_dir))

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not all_errors,
                "blocking_errors": all_errors,
                "warnings": [],
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(CLEAN if (not all_errors or check) else VALIDATION_FAILURE)

    if all_errors:
        for err in all_errors:
            click.echo(err)
        click.echo(
            "check_script_conventions: FAIL — a gate script does not follow the "
            "naming, docstring, or import conventions",
            err=True,
        )
        raise ctx.exit(CLEAN if check else VALIDATION_FAILURE)

    click.echo("All conventions checks passed.")
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
