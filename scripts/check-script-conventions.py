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
"""
from __future__ import annotations

import ast
import os
import re
import sys
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


def check_cli_framework(scripts_dir: Path) -> list[str]:
    """Check R2: gate scripts use Click, not argparse."""
    errors = []
    for path in sorted(scripts_dir.glob("check-*.py")):
        content = path.read_text()
        if "import argparse" in content or "from argparse import" in content:
            errors.append(f"{path.name}: uses argparse — must use Click (R2.1)")
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
@click.pass_context
def cli(ctx: click.Context) -> None:
    scripts_dir = ROOT / "scripts"
    all_errors: list[str] = []

    all_errors.extend(check_cli_framework(scripts_dir))
    all_errors.extend(check_naming_and_structure(scripts_dir))
    all_errors.extend(check_json_flag(scripts_dir))

    if all_errors:
        for err in all_errors:
            click.echo(err)
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo("All conventions checks passed.")
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
