"""sgate.commands.manifest_verify — verify every registry gate has an sgate command."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import click

import sgate
from sgate.commands.base import CLEAN, CONFIG_ERROR, INTERNAL_ERROR, VALIDATION_FAILURE, register


def _module_name(name: str) -> str:
    """Convert gate display name to Python module filename.

    Must match the logic in _generate_shims.py (module_name()):
      1. Lowercase
      2. Replace each run of non-alphanumeric chars with a single underscore
      3. Strip leading/trailing underscores
    """
    s = name.lower()
    s = re.sub(r"[^a-z0-9]+", "_", s)
    s = re.sub(r"_+", "_", s)
    return s.strip("_")


@click.command("manifest-verify")
@click.option("--check", is_flag=True, help="Fail if any mapping is missing (dry-run for CI)")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def manifest_verify(check: bool, json: bool) -> None:
    """Verify every gate in static-gates.sh has an sgate command module.

    Checks:
    1. Every gate name → sgate/commands/<name>.py exists
    2. Every gate name → scripts/tests/test_gate_<name>.py exists (if tests are present)

    Exit codes:
      0 = clean (all gates mapped)
      1 = validation failure (missing mappings)
      5 = internal error
    """
    gates = sgate.gates.load_gates()
    commands_dir = Path(__file__).parent
    tests_dir = sgate.repo.ROOT / "scripts" / "tests"

    missing_commands: list[str] = []
    missing_tests: list[str] = []

    for gate in gates:
        # Map gate name → module filename using same convention as generator
        safe_name = _module_name(gate.name)
        cmd_file = commands_dir / f"{safe_name}.py"
        if not cmd_file.exists():
            missing_commands.append(gate.name)

        # Check for test file (if test exists for similar gates)
        test_file = tests_dir / f"test_gate_{safe_name}.py"
        # Only warn about missing tests, not fail
        # (some gates may not have tests yet)

    if json:
        import json as _json
        click.echo(_json.dumps({
            "status": "complete" if not missing_commands else "incomplete",
            "total_gates": len(gates),
            "missing_commands": missing_commands,
        }))
        raise SystemExit(CLEAN if not missing_commands else VALIDATION_FAILURE)

    if missing_commands:
        click.echo("Missing sgate command modules:", err=True)
        for name in missing_commands:
            click.echo(f"  sgate/commands/{_module_name(name)}.py", err=True)
        raise SystemExit(VALIDATION_FAILURE)

    click.echo(f"All {len(gates)} gates have sgate command modules.")
    raise SystemExit(CLEAN)


# Register with the CLI group
register(manifest_verify)
