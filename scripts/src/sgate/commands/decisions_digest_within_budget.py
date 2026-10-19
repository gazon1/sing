"""sgate.commands.decisions_digest_within_budget — custom gate: DIGEST.md within budget."""
from __future__ import annotations

import re
import sys

import click

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


class DecisionsDigestPayload(GatePayload):
    """Combined payload: the gate's one remaining step."""

    digest: DigestBudgetPayload | None = None


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


@click.command("decisions_digest_within_budget")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def decisions_digest_within_budget(check: bool, emit_json: bool) -> None:
    """Run the "decisions digest within budget" gate.

    Runs one script:
      1. python3 scripts/refresh-decisions-digest.py   (checks DIGEST.md line budget)

    It used to run a second step over every agent-facing document, in a
    doc-sizes script. That script was removed on 2026-10-19 together with the
    `doc sizes are within budget` gate; the digest budget stayed, because the
    generator that prints it is the thing that holds the budget.
    """
    repo_root = sgate.repo.ROOT

    digest_script = repo_root / "scripts/refresh-decisions-digest.py"

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

    if emit_json:
        DecisionsDigestPayload(
            gate=GATE_NAME,
            passed=digest.passed,
            digest=digest,
            blocking_errors=digest.blocking_errors,
        ).emit_and_exit(result1.returncode, emit_json=True)
    elif check:
        gate_warn(GATE_NAME, None, None, None, None, "decisions digest within budget")
    elif not digest.honest_pass(result1.returncode):
        gate_fail(GATE_NAME, None, None, None, None, "digest budget exceeded")
        raise SystemExit(1)

    if digest.honest_pass(result1.returncode) and not emit_json:
        click.echo(
            f"decisions_digest_within_budget: DIGEST.md {digest.digest_lines}/"
            f"{digest.digest_budget}, DIGEST-full.md {digest.digest_full_lines}/"
            f"{digest.digest_full_budget} — PASS"
        )


register(decisions_digest_within_budget)
