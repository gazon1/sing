"""sgate.commands.skill_drift — custom gate: skill tool listings match the code surface."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


# "check-skill-drift: OK — {N} skill(s) match the ADR tool surface ({M} command(s), {K} MCP tool(s))"
_OK_PATTERN = re.compile(
    r"check-skill-drift: OK — (\d+) skill\(s\) match the ADR tool surface "
    r"\((\d+) command\(s\), (\d+) MCP tool\(s\)\)"
)
_ERROR_PREFIX = "ERROR: "


def _parse_skill_drift(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    combined = (stdout + "\n" + stderr).strip()

    passed = exit_code == 0
    skills_count = 0
    commands_count = 0
    mcp_tools_count = 0
    errors: list[str] = []

    for line in combined.splitlines():
        line = line.strip()
        if line.startswith(_ERROR_PREFIX):
            errors.append(line[len(_ERROR_PREFIX):])

    m_ok = _OK_PATTERN.search(combined)
    if m_ok:
        skills_count = int(m_ok.group(1))
        commands_count = int(m_ok.group(2))
        mcp_tools_count = int(m_ok.group(3))

    return {
        "passed": passed,
        "skills_checked": skills_count,
        "commands_count": commands_count,
        "mcp_tools_count": mcp_tools_count,
        "errors": errors,
        "blocking_errors": errors if not passed else [],
        "warnings": [],
    }


@click.command("skills_describe_the_tools_that_exist")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def skills_describe_the_tools_that_exist(check: bool, emit_json: bool) -> None:
    """Run the "skill drift" gate.

    Calls: python3 scripts/check-skill-drift.py
    Output pass: "check-skill-drift: OK — {N} skill(s) match the ADR tool surface ..."
    Output fail: ERROR lines + suggestion to run render-adr-tools-section.py
    Exit 0: clean; Exit 1: validation failure
    """
    repo_root = sgate.repo.ROOT
    gate_name = "skills describe the tools that exist"
    script = repo_root / "scripts/check-skill-drift.py"

    result = run_capture([sys.executable, str(script)], gate_name)
    parsed = _parse_skill_drift(result.stdout, result.stderr, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
    elif check:
        if parsed["passed"]:
            gate_warn(
                gate_name,
                None,
                None,
                None,
                None,
                f"skill drift OK — {parsed['skills_checked']} skills, "
                f"{parsed['commands_count']} commands, {parsed['mcp_tools_count']} MCP tools",
            )
        else:
            print(f"skill_drift: {len(parsed['errors'])} drift(s)")
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
                f"skill ADR tool surface drift: {len(parsed['errors'])} mismatch(es)",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo(
            f"skill_drift: {parsed['skills_checked']} skills match ADR tool surface "
            f"({parsed['commands_count']} commands, {parsed['mcp_tools_count']} MCP tools) — PASS"
        )


register(skills_describe_the_tools_that_exist)
