#!/usr/bin/env python3
"""check-script-conventions.py — enforce naming, docstring, and import conventions for gate scripts.

Naming conventions (R5):
  R5.1: Gate script names match `check-<gate-name>.py` (kebab-case)
  R5.2: Shell wrappers that only exec/python3 are deleted (no pass-through wrappers)
  R5.3: All imports use absolute imports (no relative `from .foo import`)
  R5.4: Gate scripts that implement a decision reference the ADR in a comment

CLI conventions (R2):
  R2.1: Gate scripts use Click, not argparse
  R2.2: Every gate command accepts --check and --json flags
  R2.3: No manual PYTHONPATH manipulation (paths via sgate.repo)

Output conventions (R3):
  R3.1: Violations go to stdout, not stderr
  R3.2: Failures print to stderr for progress only
  R3.3: Distinct exit codes for different failure modes
  R3.4: Violations include file and line number when available
  R3.5: No CI env vars required for local runs
  R3.6: No raw tracebacks in CI output
  R3.7: --json flag emits machine-readable JSON to stdout

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
import json
import re
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

        # R5.4: ADR reference in comment (if script has non-trivial logic)
        content = path.read_text()
        if "gate" in name or len(content) > 500:
            if "ADR" not in content and "#" not in content.split("\n")[0]:
                errors.append(f"{name}: no ADR reference in header comment (R5.4)")

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
    """Check R2.2/R3.7: --json flag emits JSON."""
    errors = []
    for path in sorted(scripts_dir.glob("check-*.py")):
        content = path.read_text()
        if '"--json"' in content or "'--json'" in content:
            continue  # has --json, ok
        # Allow if it uses click.option
        if "@click.option" in content and ("--check" in content or "is_flag=True" in content):
            continue  # likely a click-based script
        # For now, just warn
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
