"""sgate.commands.skill_frontmatter — custom gate: skill SKILL.md frontmatter is valid YAML."""
from __future__ import annotations

import re
import sys

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.models import GatePayload
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register

GATE_NAME = "skill frontmatter"


class SkillFrontmatterPayload(GatePayload):
    """Typed payload for the skill-frontmatter gate."""

    skills_checked: int = 0
    error_count: int = 0
    errors: list[str] = []


# "check_skill_frontmatter.sh: OK — {N} skill file(s) parse as YAML ..."
_OK_PATTERN = re.compile(
    r"check_skill_frontmatter\.sh: OK — (\d+) skill file\(s\) parse as YAML"
)
# "check_skill_frontmatter.sh: {N} error(s) in {M} skill file(s)"
_ERROR_PATTERN = re.compile(
    r"check_skill_frontmatter\.sh: (\d+) error\(s\) in (\d+) skill file\(s\)"
)
_ERROR_PREFIX = "ERROR: "


def _parse_skill_frontmatter(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> SkillFrontmatterPayload:
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

    return SkillFrontmatterPayload(
        gate=GATE_NAME,
        passed=passed,
        skills_checked=skills_count,
        error_count=error_count,
        errors=errors,
        blocking_errors=errors if not passed else [],
    )


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
    script = repo_root / "scripts/check-skill-frontmatter.sh"

    result = run_capture([str(script)], GATE_NAME)
    payload = _parse_skill_frontmatter(result.stdout, result.stderr, result.returncode)

    if emit_json:
        payload.emit_and_exit(result.returncode, emit_json=True)
    elif check:
        if payload.honest_pass(result.returncode):
            gate_warn(GATE_NAME, None, None, None, None, f"skill frontmatter OK — {payload.skills_checked} skills")
        else:
            print(f"skill_frontmatter: {payload.error_count} error(s)")
            for e in payload.errors:
                print(f"  {e}")
    else:
        if not payload.honest_pass(result.returncode):
            gate_fail(
                GATE_NAME,
                None,
                None,
                None,
                None,
                f"{payload.error_count} skill frontmatter error(s)",
            )
            raise SystemExit(1)

    if payload.honest_pass(result.returncode) and not emit_json:
        click.echo(f"skill_frontmatter: {payload.skills_checked} skills parse as YAML — PASS")


register(skill_frontmatter)
