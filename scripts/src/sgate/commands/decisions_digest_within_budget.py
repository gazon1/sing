"""sgate.commands.decisions_digest_within_budget — custom gate: DIGEST.md within budget."""
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

GATE_NAME = "decisions_digest_within_budget"


class DigestBudgetPayload(GatePayload):
    """Typed payload for the DIGEST line-budget step."""

    step: str = "refresh-decisions-digest"
    # Two digests, two budgets. `digest_lines`/`digest_budget` are the always-loaded
    # orientation layer (DIGEST.md); `digest_full_*` are the on-demand complete index.
    digest_lines: int = 0
    digest_budget: int = 0
    digest_full_lines: int = 0
    digest_full_budget: int = 0


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


# From refresh-decisions-digest.py, which prints one line naming both digests:
#   refresh-decisions-digest: DIGEST.md is 75 lines (budget: 120), DIGEST-full.md is 1950 lines (budget: 3000), 719 entries
#
# This pattern used to look for "DIGEST.md is N lines (budget: M)" while the script
# printed "WARNING: DIGEST is N lines (limit: M)" — a different phrase AND a different
# word — so it never matched anything. The step then reported passed=exit_code==0, and the
# script always exits 0, so the digest budget was never enforced here at all: the only
# thing actually holding it was check-doc-sizes.py, a step away. A gate that parses a
# string nobody prints is not a gate, it is a comment that costs a process spawn.
_DIGEST_BUDGET_PATTERN = re.compile(
    r"DIGEST(?P<full>-full)?\.md is (?P<lines>\d+) lines \(budget: (?P<budget>\d+)\)"
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
    text = stdout.strip()

    passed = exit_code == 0
    digest_lines = 0
    digest_budget = 0
    full_lines = 0
    full_budget = 0

    # Keyed off the optional `-full` group rather than match position, so this stays
    # correct if the generator ever prints the two digests in the other order.
    for match in _DIGEST_BUDGET_PATTERN.finditer(text):
        lines_n = int(match.group("lines"))
        budget_n = int(match.group("budget"))
        if match.group("full"):
            full_lines, full_budget = lines_n, budget_n
        else:
            digest_lines, digest_budget = lines_n, budget_n

    errors: list[str] = []
    if digest_lines:
        errors.append(f"DIGEST.md is {digest_lines} lines (budget: {digest_budget})")
    if full_lines:
        errors.append(f"DIGEST-full.md is {full_lines} lines (budget: {full_budget})")

    return DigestBudgetPayload(
        gate=GATE_NAME,
        passed=passed,
        digest_lines=digest_lines,
        digest_budget=digest_budget,
        digest_full_lines=full_lines,
        digest_full_budget=full_budget,
        blocking_errors=errors if not passed else [],
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
        detail = (
            f"DIGEST.md {digest.digest_lines}/{digest.digest_budget}, "
            f"DIGEST-full.md {digest.digest_full_lines}/{digest.digest_full_budget}"
        )
        if emit_json:
            digest.emit_and_exit(result1.returncode, emit_json=True)
        elif check:
            gate_warn(GATE_NAME, None, None, None, None, f"digest budget exceeded: {detail}")
        else:
            gate_fail(GATE_NAME, None, None, None, None, f"digest budget exceeded: {detail}")
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
        click.echo(
            f"decisions_digest_within_budget: DIGEST.md {digest.digest_lines}/"
            f"{digest.digest_budget}, DIGEST-full.md {digest.digest_full_lines}/"
            f"{digest.digest_full_budget}, doc sizes OK — PASS"
        )


register(decisions_digest_within_budget)
