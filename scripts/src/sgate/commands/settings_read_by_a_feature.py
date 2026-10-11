"""sgate.commands.dead_settings — custom gate: persisted settings with no consumer."""
from __future__ import annotations

import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register

_STDIN_TYPES = ("commonMain", "androidMain", "jvmMain")


def _parse_dead_settings(stdout: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    stdout = stdout.strip()
    passed = exit_code == 0
    findings: list[str] = []
    if exit_code == 1 and stdout:
        # --quiet prints one [dead-setting] per line
        for line in stdout.splitlines():
            line = line.strip()
            if line.startswith("[dead-setting]"):
                findings.append(line.split("[dead-setting]", 1)[1].strip())
            elif line and not line.startswith("check-dead-settings:"):
                findings.append(line)
    return {
        "passed": passed,
        "dead_settings_count": len(findings),
        "findings": findings,
        "blocking_errors": [] if passed else [f"{len(findings)} dead setting(s) found"],
        "warnings": [],
    }


@click.command("settings_read_by_a_feature")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def settings_read_by_a_feature(check: bool, emit_json: bool) -> None:
    """Run the "dead settings" gate.

    Calls: python3 scripts/check-dead-settings.py --quiet
    Output on failure: [dead-setting] Section.field — ... (one per finding)
    Exit 0: no dead settings; Exit 1: one or more dead settings
    """
    repo_root = sgate.repo.ROOT
    gate_name = "settings read by a feature"
    script = repo_root / "scripts/check-dead-settings.py"

    result = run_capture([sys.executable, str(script), "--quiet"], gate_name)
    parsed = _parse_dead_settings(result.stdout, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
    elif check:
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, "dead_settings check passed")
        else:
            print(f"dead_settings: {parsed['dead_settings_count']} dead setting(s) found")
            for f in parsed["findings"]:
                print(f"  {f}")
    else:
        if not parsed["passed"]:
            gate_fail(
                gate_name,
                None,
                None,
                None,
                None,
                f"{parsed['dead_settings_count']} dead setting(s) found",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo(f"dead_settings: {parsed['dead_settings_count']} dead settings — PASS")


register(settings_read_by_a_feature)
