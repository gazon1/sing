"""sgate.commands.adr_references — custom gate: ADR references are valid."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


# Pattern: "check_adr_references.py: OK — {N} ADR slugs, {M} baselined..."
# or:       "check_adr_references.py: {N} dangling ADR reference(s) ..."
_OK_PATTERN = re.compile(
    r"check_adr_references\.py: OK — (\d+) ADR slugs,"
)
_DANGLING_PATTERN = re.compile(
    r"check_adr_references\.py: (\d+) dangling ADR reference\(s\)"
)
_STALE_PATTERN = re.compile(
    r"check_adr_references: (\d+) baseline entr\(ies\) no longer occur"
)


def _parse_adr_references(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    combined = (stdout + "\n" + stderr).strip()

    passed = exit_code == 0
    dangling_count = 0
    slugs_count = 0
    stale_count = 0
    errors: list[str] = []

    m_dangle = _DANGLING_PATTERN.search(combined)
    if m_dangle:
        dangling_count = int(m_dangle.group(1))

    m_ok = _OK_PATTERN.search(combined)
    if m_ok:
        slugs_count = int(m_ok.group(1))

    m_stale = _STALE_PATTERN.search(combined)
    if m_stale:
        stale_count = int(m_stale.group(1))

    if not passed:
        errors.append(f"{dangling_count} dangling ADR reference(s)")
    if stale_count > 0:
        errors.append(f"{stale_count} baseline entry/entries no longer occur — remove with --update-baseline")

    return {
        "passed": passed,
        "adr_slugs_count": slugs_count,
        "dangling_count": dangling_count,
        "stale_baseline_count": stale_count,
        "blocking_errors": errors,
        "warnings": [],
    }


@click.command("adr_references_are_valid")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def adr_references_are_valid(check: bool, emit_json: bool) -> None:
    """Run the "ADR references are valid" gate.

    Calls: python3 scripts/check-adr-references.py
    Output pass: "check_adr_references.py: OK — {N} ADR slugs, ..."
    Output fail: "check_adr_references.py: {N} dangling ADR reference(s) ..."
    Exit 0: clean; Exit 1: validation failure
    """
    repo_root = sgate.repo.ROOT
    gate_name = "ADR references are valid"
    script = repo_root / "scripts/check-adr-references.py"

    result = run_capture([sys.executable, str(script)], gate_name)
    parsed = _parse_adr_references(result.stdout, result.stderr, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
    elif check:
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, f"ADR references OK — {parsed['adr_slugs_count']} slugs, {parsed['dangling_count']} dangling")
        else:
            print(f"adr_references: {parsed['dangling_count']} dangling reference(s)")
    else:
        if not parsed["passed"]:
            gate_fail(
                gate_name,
                None,
                None,
                None,
                None,
                f"{parsed['dangling_count']} dangling ADR reference(s)",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo(f"adr_references: {parsed['adr_slugs_count']} ADR slugs, {parsed['dangling_count']} dangling — PASS")


register(adr_references_are_valid)
