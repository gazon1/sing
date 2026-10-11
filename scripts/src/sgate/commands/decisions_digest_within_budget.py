"""sgate.commands.decisions_digest_within_budget — custom gate: DIGEST.md within budget."""
from __future__ import annotations

import re
import sys
from typing import Any

import click

import sgate
from sgate.commands._runner import run_capture
from sgate.output import gate_fail, gate_warn
from sgate.commands.base import register


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


def _parse_refresh_digest(stdout: str | bytes, exit_code: int) -> dict[str, Any]:
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

    return {
        "passed": passed,
        "digest_lines": digest_lines,
        "digest_budget": digest_budget,
        "blocking_errors": (
            [] if passed else [f"DIGEST.md is {digest_lines} lines (budget: {digest_budget})"]
        ),
    }


def _parse_doc_sizes_from_combined(combined: str) -> dict[str, Any]:
    violations: list[dict[str, str]] = []
    for m in _VIOLATION_PATTERN.finditer(combined):
        violations.append({"document": m.group(1).strip(), "detail": m.group(2).strip()})

    passed = bool(_OK_PATTERN.search(combined)) and not violations
    errors: list[str] = [f"{v['document']}: {v['detail']}" for v in violations]

    return {
        "passed": passed,
        "violations": violations,
        "violation_count": len(violations),
        "blocking_errors": errors,
    }


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
    gate_name = "decisions_digest_within_budget"

    digest_script = repo_root / "scripts/refresh-decisions-digest.py"
    sizes_script = repo_root / "scripts/check-doc-sizes.py"

    # Step 1: refresh-decisions-digest.py
    result1 = run_capture([sys.executable, str(digest_script)], gate_name + " [refresh-decisions-digest]")
    parsed1 = _parse_refresh_digest(result1.stdout, result1.returncode)

    if not parsed1["passed"]:
        if emit_json:
            import json

            print(json.dumps({"gate": gate_name, "step": "refresh-decisions-digest", **parsed1}, indent=2))
        elif check:
            gate_warn(gate_name, None, None, None, None, f"DIGEST.md budget exceeded: {parsed1['digest_lines']} lines (budget {parsed1['digest_budget']})")
        else:
            gate_fail(gate_name, None, None, None, None, f"DIGEST.md is {parsed1['digest_lines']} lines (budget: {parsed1['digest_budget']})")
            raise SystemExit(1)
        return

    # Step 2: check-doc-sizes.py
    result2 = run_capture([sys.executable, str(sizes_script)], gate_name + " [check-doc-sizes]")
    combined = result2.stdout + b"\n" + result2.stderr if isinstance(result2.stdout, bytes) else result2.stdout + "\n" + result2.stderr
    parsed2 = _parse_doc_sizes_from_combined(combined if isinstance(combined, str) else combined.decode("utf-8", errors="replace"))

    if emit_json:
        import json

        print(json.dumps({"gate": gate_name, "digest": parsed1, "doc_sizes": parsed2}, indent=2))
    elif check:
        if parsed2["passed"]:
            gate_warn(gate_name, None, None, None, None, f"decisions digest within budget, doc sizes OK")
        else:
            gate_warn(gate_name, None, None, None, None, f"doc sizes: {parsed2['violation_count']} violation(s)")
            for v in parsed2["violations"]:
                print(f"  {v['document']}: {v['detail']}")
    else:
        if not parsed2["passed"]:
            gate_fail(gate_name, None, None, None, None, f"{parsed2['violation_count']} doc size budget violation(s)")
            raise SystemExit(1)

    if parsed1["passed"] and parsed2["passed"] and not emit_json:
        click.echo("decisions_digest_within_budget: DIGEST.md within budget, doc sizes OK — PASS")


register(decisions_digest_within_budget)
