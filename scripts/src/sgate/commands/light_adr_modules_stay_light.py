"""sgate.commands.adr_module_boundary — custom gate: light ADR modules must stay light."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


# "light ADR modules reach no application module"
_OK_PATTERN = re.compile(r"light ADR modules reach no application module")
# "  :adr-cli -> :shared, :androidApp"
_DEPS_PATTERN = re.compile(r"^\s+(\S+)\s+->\s+(.+)$", re.MULTILINE)
# "light ADR modules reach the application runtime:" (stderr header)
_RUNTIME_HEADER = re.compile(r"light ADR modules reach the application runtime:")


def _parse_adr_module_boundary(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")

    passed = exit_code == 0
    findings: list[dict[str, str]] = []
    errors: list[str] = []

    if _OK_PATTERN.search(stdout):
        pass  # no findings
    elif _RUNTIME_HEADER.search(stderr):
        # Parse "  {light} -> {app}" from stderr
        for m in _DEPS_PATTERN.finditer(stderr):
            light = m.group(1).strip()
            apps = m.group(2).strip()
            findings.append({"light_module": light, "reaches": apps})
            errors.append(f"{light} -> {apps}")

    return {
        "passed": passed,
        "findings": findings,
        "blocking_errors": errors,
        "warnings": [],
    }


@click.command("light_adr_modules_stay_light")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--quiet", is_flag=True, help="Suppress verbose output from the underlying script")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def light_adr_modules_stay_light(check: bool, quiet: bool, emit_json: bool) -> None:
    """Run the "ADR module boundary" gate.

    Calls: python3 scripts/check-adr-module-boundary.py
    Output pass: "light ADR modules reach no application module"
    Output fail: "light ADR modules reach the application runtime:" + "  :adr-cli -> :shared" lines (to stderr)
    Exit 0: clean; Exit 1: boundary violation; Exit 2: parse failure
    """
    repo_root = sgate.repo.ROOT
    gate_name = "light ADR modules stay light"
    script = repo_root / "scripts/check-adr-module-boundary.py"

    cmd = [sys.executable, str(script)]
    if quiet:
        cmd.append("--quiet")
    result = run_capture(cmd, gate_name)
    parsed = _parse_adr_module_boundary(result.stdout, result.stderr, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
        raise SystemExit(0 if parsed["passed"] else 1)
    elif check:
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, "ADR module boundary OK — no light module reaches application runtime")
        else:
            print(f"adr_module_boundary: {len(parsed['findings'])} boundary violation(s)")
            for f in parsed["findings"]:
                print(f"  {f['light_module']} -> {f['reaches']}")
    else:
        if not parsed["passed"]:
            finding_str = "; ".join(parsed["blocking_errors"])
            gate_fail(
                gate_name,
                None,
                None,
                None,
                None,
                f"light module reaches application runtime: {finding_str}",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo("adr_module_boundary: no light ADR module reaches application runtime — PASS")


register(light_adr_modules_stay_light)
