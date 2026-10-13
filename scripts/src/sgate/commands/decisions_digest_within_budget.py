"""sgate.commands.decisions_digest_within_budget — custom gate: DIGEST.md within budget."""
from __future__ import annotations

import re
import sys

import click
from pydantic import BaseModel

import sgate
from sgate.commands._runner import run_capture
from sgate.models import GatePayload
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register

GATE_NAME = "decisions_digest_within_budget"


class DigestBudgetPayload(GatePayload):
    """Typed payload for the DIGEST.md line-budget step."""

    step: str = "refresh-decisions-digest"
    digest_lines: int = 0
    digest_budget: int = 0


class DocSizeViolation(BaseModel):
    """One budget-exceeded document."""

    document: str
    detail: str


class DocSizesPayload(GatePayload):
    """Typed payload for the doc-sizes step."""

    violations: list[DocSizeViolation] = []
    violation_count: int = 0


class DecisionsDigestPayload(GatePayload):
    """Combined payload: both steps of the gate."""

    digest: DigestBudgetPayload | None = None
    doc_sizes: DocSizesPayload | None = None


# From refresh-decisions-digest.py:
# - On success (exit 0): silent, no stdout
# - On budget exceeded: prints budget info to stdout, exits non-zero
_DIGEST_BUDGET_PATTERN = re.compile(
    r"DIGEST\.md is (\d+) lines \(budget: (\d+)\)"
)

# From check-doc-sizes.py:
# "Doc sizes OK: AGENTS.md <= {N}, DIGEST.md <= {M}, ..."
_OK_PATTERN = re.compile(
    r"Doc sizes OK: AGENTS\.md <= \d+, DIGEST\.md <= \d+, "
    r"ARCHITECTURE\.md <= \d+, PROGRESS\.md <= \d+, "
    r"SKILL\.md <= \d+ lines, description <= \d+ chars"
)
# "Doc size budgets exceeded:\n  {name}: {detail}"
_VIOLATION_PATTERN = re.compile(r"^\s+([^:]+):\s+(.+)$", re.MULTILINE)


def _parse_refresh_digest(stdout: str | bytes, exit_code: int) -> DigestBudgetPayload:
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    stdout = stdout.strip()

    passed = exit_code == 0
    digest_lines = 0
    digest_budget = 0

    m = _DIGEST_BUDGET_PATTERN.search(stdout)
    if m:
        digest_lines = int(m.group(1))
        digest_budget = int(m.group(2))

    return DigestBudgetPayload(
        gate=GATE_NAME,
        passed=passed,
        digest_lines=digest_lines,
        digest_budget=digest_budget,
        blocking_errors=(
            [] if passed else [f"DIGEST.md is {digest_lines} lines (budget: {digest_budget})"]
        ),
    )


def _parse_doc_sizes_from_combined(combined: str, exit_code: int) -> DocSizesPayload:
    violations = [
        DocSizeViolation(document=m.group(1).strip(), detail=m.group(2).strip())
        for m in _VIOLATION_PATTERN.finditer(combined)
    ]
    passed = exit_code == 0 and bool(_OK_PATTERN.search(combined)) and not violations
    errors: list[str] = [f"{v.document}: {v.detail}" for v in violations]

    return DocSizesPayload(
        gate=GATE_NAME,
        passed=passed,
        violations=violations,
        violation_count=len(violations),
        blocking_errors=errors,
    )


@click.command("decisions_digest_within_budget")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def decisions_digest_within_budget(check: bool, emit_json: bool) -> None:
    """Run the "decisions digest within budget" gate.

    Runs two scripts sequentially:
      1. python3 scripts/refresh-decisions-digest.py   (checks DIGEST.md line budget)
      2. python3 scripts/check-doc-sizes.py            (checks all doc sizes)

    Either script failing causes this gate to fail.
    """
    repo_root = sgate.repo.ROOT

    digest_script = repo_root / "scripts/refresh-decisions-digest.py"
    sizes_script = repo_root / "scripts/check-doc-sizes.py"

    # Step 1: refresh-decisions-digest.py
    result1 = run_capture([sys.executable, str(digest_script)], GATE_NAME + " [refresh-decisions-digest]")
    digest = _parse_refresh_digest(result1.stdout, result1.returncode)

    if not digest.honest_pass(result1.returncode):
        if emit_json:
            digest.emit_and_exit(result1.returncode, emit_json=True)
        elif check:
            gate_warn(GATE_NAME, None, None, None, None, f"DIGEST.md budget exceeded: {digest.digest_lines} lines (budget {digest.digest_budget})")
        else:
            gate_fail(GATE_NAME, None, None, None, None, f"DIGEST.md is {digest.digest_lines} lines (budget: {digest.digest_budget})")
            raise SystemExit(1)
        return

    # Step 2: check-doc-sizes.py
    result2 = run_capture([sys.executable, str(sizes_script)], GATE_NAME + " [check-doc-sizes]")
    combined = result2.stdout + b"\n" + result2.stderr if isinstance(result2.stdout, bytes) else result2.stdout + "\n" + result2.stderr
    sizes = _parse_doc_sizes_from_combined(
        combined if isinstance(combined, str) else combined.decode("utf-8", errors="replace"),
        result2.returncode,
    )

    if emit_json:
        DecisionsDigestPayload(
            gate=GATE_NAME,
            passed=digest.passed and sizes.passed,
            digest=digest,
            doc_sizes=sizes,
            blocking_errors=sizes.blocking_errors,
        ).emit_and_exit(result2.returncode, emit_json=True)
    elif check:
        if sizes.honest_pass(result2.returncode):
            gate_warn(GATE_NAME, None, None, None, None, f"decisions digest within budget, doc sizes OK")
        else:
            gate_warn(GATE_NAME, None, None, None, None, f"doc sizes: {sizes.violation_count} violation(s)")
            for v in sizes.violations:
                print(f"  {v.document}: {v.detail}")
    else:
        if not sizes.honest_pass(result2.returncode):
            gate_fail(GATE_NAME, None, None, None, None, f"{sizes.violation_count} doc size budget violation(s)")
            raise SystemExit(1)

    if digest.honest_pass(result1.returncode) and sizes.honest_pass(result2.returncode) and not emit_json:
        click.echo("decisions_digest_within_budget: DIGEST.md within budget, doc sizes OK — PASS")


register(decisions_digest_within_budget)
