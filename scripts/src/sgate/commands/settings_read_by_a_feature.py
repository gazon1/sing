"""sgate.commands.dead_settings — custom gate: persisted settings with no consumer."""
from __future__ import annotations

import sys

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.commands.base import register
from sgate.models import GatePayload
from sgate.output import gate_fail, gate_warn

GATE_NAME = "settings read by a feature"


class DeadSettingsPayload(GatePayload):
    """Typed payload for the dead-settings gate."""

    dead_settings_count: int = 0
    findings: list[str] = []


def _parse_dead_settings(stdout: str | bytes, exit_code: int) -> DeadSettingsPayload:
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
    return DeadSettingsPayload(
        gate=GATE_NAME,
        passed=passed,
        dead_settings_count=len(findings),
        findings=findings,
        blocking_errors=[] if passed else [f"{len(findings)} dead setting(s) found"],
    )


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
    script = repo_root / "scripts/check-dead-settings.py"

    result = run_capture([sys.executable, str(script), "--quiet"], GATE_NAME)
    payload = _parse_dead_settings(result.stdout, result.returncode)

    if emit_json:
        payload.emit_and_exit(result.returncode, emit_json=True)
    elif check:
        if payload.honest_pass(result.returncode):
            gate_warn(GATE_NAME, None, None, None, None, "dead_settings check passed")
        else:
            print(f"dead_settings: {payload.dead_settings_count} dead setting(s) found")
            for f in payload.findings:
                print(f"  {f}")
    else:
        if not payload.honest_pass(result.returncode):
            gate_fail(
                GATE_NAME,
                None,
                None,
                None,
                None,
                f"{payload.dead_settings_count} dead setting(s) found",
            )
            raise SystemExit(1)

    if payload.honest_pass(result.returncode) and not emit_json:
        click.echo(f"dead_settings: {payload.dead_settings_count} dead settings — PASS")


register(settings_read_by_a_feature)
