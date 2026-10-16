"""sgate.commands.doc_sizes — custom gate: agent-facing doc sizes are within budget."""
from __future__ import annotations

import re
import sys

import click
from pydantic import BaseModel

import sgate
from sgate.commands._runner import run_capture
from sgate.commands.base import register
from sgate.models import GatePayload
from sgate.output import gate_fail, gate_warn

GATE_NAME = "doc_sizes"


class DocSizeViolation(BaseModel):
    """One budget-exceeded document."""

    document: str
    detail: str


class DocSizesPayload(GatePayload):
    """Typed payload for the doc-sizes gate."""

    violations: list[DocSizeViolation] = []
    violation_count: int = 0


# "Doc sizes OK: AGENTS.md <= {N}, DIGEST.md <= {M}, ..."
_OK_PATTERN = re.compile(
    r"Doc sizes OK: AGENTS\.md <= \d+, DIGEST\.md <= \d+, "
    r"ARCHITECTURE\.md <= \d+, PROGRESS\.md <= \d+, "
    r"SKILL\.md <= \d+ lines, description <= \d+ chars"
)
# "Doc size budgets exceeded:\n  {name}: {detail}"
_VIOLATION_PATTERN = re.compile(r"^\s+([^:]+):\s+(.+)$", re.MULTILINE)


def _parse_doc_sizes(stdout: str | bytes, stderr: str | bytes, exit_code: int) -> DocSizesPayload:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    combined = (stdout + "\n" + stderr).strip()

    passed = bool(exit_code == 0 and _OK_PATTERN.search(combined))
    violations = [
        DocSizeViolation(document=m.group(1).strip(), detail=m.group(2).strip())
        for m in _VIOLATION_PATTERN.finditer(combined)
    ]
    errors: list[str] = [f"{v.document}: {v.detail}" for v in violations]

    return DocSizesPayload(
        gate=GATE_NAME,
        passed=passed,
        violations=violations,
        violation_count=len(violations),
        blocking_errors=errors if violations else [],
    )


@click.command("doc_sizes")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def doc_sizes(check: bool, emit_json: bool) -> None:
    """Run the "doc sizes" gate.

    Calls: python3 scripts/check-doc-sizes.py
    Output pass: "Doc sizes OK: AGENTS.md <= {N}, ..."
    Output fail: "Doc size budgets exceeded:" + "  {name}: {detail}" lines
    Exit 0: within budget; Exit 1: budget exceeded
    """
    repo_root = sgate.repo.ROOT
    script = repo_root / "scripts/check-doc-sizes.py"

    result = run_capture([sys.executable, str(script)], GATE_NAME)
    payload = _parse_doc_sizes(result.stdout, result.stderr, result.returncode)

    if emit_json:
        payload.emit_and_exit(result.returncode, emit_json=True)
    elif check:
        if payload.honest_pass(result.returncode):
            gate_warn(GATE_NAME, None, None, None, None, f"doc sizes OK — {payload.violation_count} violations")
        else:
            print(f"doc_sizes: {payload.violation_count} budget violation(s)")
            for v in payload.violations:
                print(f"  {v.document}: {v.detail}")
    else:
        if not payload.honest_pass(result.returncode):
            gate_fail(
                GATE_NAME,
                None,
                None,
                None,
                None,
                f"{payload.violation_count} doc size budget violation(s)",
            )
            raise SystemExit(1)

    if payload.honest_pass(result.returncode) and not emit_json:
        click.echo(f"doc_sizes: within budget — PASS")


register(doc_sizes)
