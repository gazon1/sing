"""sgate.commands.skill_frontmatter — custom gate: skill SKILL.md frontmatter is valid YAML."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


# "check_skill_frontmatter.sh: OK — {N} skill file(s) parse as YAML ..."
_OK_PATTERN = re.compile(
    r"check_skill_frontmatter\.sh: OK — (\d+) skill file\(s\) parse as YAML"
)
# "check_skill_frontmatter.sh: {N} error(s) in {M} skill file(s)"
_ERROR_PATTERN = re.compile(
    r"check_skill_frontmatter\.sh: (\d+) error\(s\) in (\d+) skill file\(s\)"
)
_ERROR_PREFIX = "ERROR: "


def _parse_skill_frontmatter(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> dict[str, Any]:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    combined = (stdout + "\n" + stderr).strip()

    passed = exit_code == 0
    skills_count = 0
    error_count = 0
    errors: list[str] = []

    for line in combined.splitlines():
        line = line.strip()
        if line.startswith(_ERROR_PREFIX):
            errors.append(line[len(_ERROR_PREFIX):])

    m_ok = _OK_PATTERN.search(combined)
    if m_ok:
        skills_count = int(m_ok.group(1))

    m_err = _ERROR_PATTERN.search(combined)
    if m_err:
        error_count = int(m_err.group(1))

    return {
        "passed": passed,
        "skills_checked": skills_count,
        "error_count": error_count,
        "errors": errors,
        "blocking_errors": errors if not passed else [],
        "warnings": [],
    }


@click.command("skill_frontmatter")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def skill_frontmatter(check: bool, emit_json: bool) -> None:
    """Run the "skill frontmatter" gate.

    Calls: ./scripts/check-skill-frontmatter.sh
    Output pass: "check_skill_frontmatter.sh: OK — {N} skill file(s) parse as YAML ..."
    Output fail: ERROR lines + "check_skill_frontmatter.sh: {N} error(s) in {M} skill file(s)"
    Exit 0: clean; Exit 1: validation failure
    """
    repo_root = sgate.repo.ROOT
    gate_name = "skill frontmatter"
    script = repo_root / "scripts/check-skill-frontmatter.sh"

    result = run_capture([str(script)], gate_name)
    parsed = _parse_skill_frontmatter(result.stdout, result.stderr, result.returncode)

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
    elif check:
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, f"skill frontmatter OK — {parsed['skills_checked']} skills")
        else:
            print(f"skill_frontmatter: {parsed['error_count']} error(s)")
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
                f"{parsed['error_count']} skill frontmatter error(s)",
            )
            raise SystemExit(1)

    if parsed["passed"] and not emit_json:
        click.echo(f"skill_frontmatter: {parsed['skills_checked']} skills parse as YAML — PASS")


register(skill_frontmatter)
