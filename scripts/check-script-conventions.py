#!/usr/bin/env python3
"""check-script-conventions.py — enforce naming, docstring, and import conventions for gate scripts.

What this gate enforces (2026-10-20, audited):
  R5.1: Gate script names match `check-<gate-name>.py` (kebab-case)
  R5.2: Shell wrappers that only exec/python3 are deleted (no pass-through wrappers)
  R2.1: Gate scripts use Click, not argparse

What it declares but does not enforce:
  R5.3: All imports use absolute imports (no relative `from .foo import`)
  R5.4: Gate scripts that implement a decision reference the ADR in a comment
  R2.2: Every gate command accepts --check and --json flags
  R2.3: No manual PYTHONPATH manipulation (paths via sgate.repo)
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
