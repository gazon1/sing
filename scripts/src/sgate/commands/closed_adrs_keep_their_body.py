"""sgate.commands.adr_immutable — custom gate: closed ADR bodies must not change."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


# "check-adr-immutable.py: {N} closed decision(s) closed since ... checked, {M} body rewrite(s) after closure"
_SUMMARY_PATTERN = re.compile(
    r"check-adr-immutable\.py: (\d+) closed decision\(s\) .* checked, (\d+) body rewrite\(s\) after closure"
)
# "check-adr-immutable.py: OK — no closed decision had its body rewritten"
_OK_PATTERN = re.compile(r"check-adr-immutable\.py: OK — no closed decision had its body rewritten")
_ERROR_PREFIX = "ERROR: "


def _parse_adr_immutable(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    combined = (stdout + "\n" + stderr).strip()

    passed = exit_code == 0
    checked_count = 0
    rewrite_count = 0
    errors: list[str] = []

    # Collect ERROR lines
    for line in combined.splitlines():
        line = line.strip()
        if line.startswith(_ERROR_PREFIX):
            # Strip "ERROR: " prefix for the error message
            err_text = line[len(_ERROR_PREFIX):]
            errors.append(err_text)

    m_summary = _SUMMARY_PATTERN.search(combined)
    if m_summary:
        checked_count = int(m_summary.group(1))
        rewrite_count = int(m_summary.group(2))
    elif _OK_PATTERN.search(combined):
        # OK line present — checked_count and rewrite_count are 0
        pass

    if not passed and rewrite_count > 0:
        errors.insert(0, f"{rewrite_count} body rewrite(s) after closure in {checked_count} checked decisions")

    return {
        "passed": passed,
        "closed_decisions_checked": checked_count,
        "body_rewrites": rewrite_count,
        "errors": errors,
        "blocking_errors": errors,
        "warnings": [],
    }


@click.command("closed_adrs_keep_their_body")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def closed_adrs_keep_their_body(check: bool, emit_json: bool) -> None:
    """Run the "ADR immutable" gate.

    Calls: python3 scripts/check-adr-immutable.py
    Output summary: "check-adr-immutable.py: {N} closed decision(s) checked, {M} body rewrite(s)"
    Output OK: "check-adr-immutable.py: OK — no closed decision had its body rewritten"
    ERROR lines printed before summary on failure.
    Exit 0: clean; Exit 1: validation failure
    """
    repo_root = sgate.repo.ROOT
    gate_name = "closed ADRs keep their body"
    script = repo_root / "scripts/check-adr-immutable.py"

    result = run_capture([sys.executable, str(script)], gate_name)
    parsed = _parse_adr_immutable(result.stdout, result.stderr, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
    elif check:
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, f"ADR immutable: {parsed['closed_decisions_checked']} checked, {parsed['body_rewrites']} rewrites")
        else:
            print(f"adr_immutable: {parsed['body_rewrites']} body rewrite(s) in {parsed['closed_decisions_checked']} checked decisions")
            for e in parsed["errors"]:
                print(f"  {e}")
    else:
        if not parsed["passed"]:
            gate_fail(
                gate_name,
                None,
                None,
                None,
                None,
                f"{parsed['body_rewrites']} body rewrite(s) after closure",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo(f"adr_immutable: {parsed['closed_decisions_checked']} closed decisions checked, {parsed['body_rewrites']} rewrites — PASS")


register(closed_adrs_keep_their_body)
