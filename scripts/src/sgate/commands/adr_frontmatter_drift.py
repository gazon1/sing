"""sgate.commands.adr_frontmatter_drift — native Click for: ADR frontmatter drift."""
from __future__ import annotations

import json
import re
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

# Pattern: [DRY RUN] 1 files need changes, 571 unchanged, 0 structurally broken, 6 not normalizable
_DRY_RUN_RE = re.compile(
    r"\[DRY RUN\]\s+(?P<files_need_changes>\d+)\s+files need changes,\s+"
    r"(?P<unchanged>\d+)\s+unchanged,\s+"
    r"(?P<broken>\d+)\s+structurally broken,\s+"
    r"(?P<not_normalizable>\d+)\s+not normalizable"
)


def _parse_adr_frontmatter_drift(stdout: str | bytes) -> dict:
    """Parse normalize-adr-frontmatter.sh output.

    Returns:
        {
          "passed": bool,
          "files_need_changes": int,
          "unchanged": int,
          "structurally_broken": int,
          "not_normalizable": int,
          "blocking_errors": list[str],   # ::error annotations
          "warnings": list[str],          # non-blocking warnings
        }
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    blocking_errors: list[str] = []
    warnings: list[str] = []

    # Parse summary line
    summary_match = _DRY_RUN_RE.search(stdout)
    if summary_match:
        files_need_changes = int(summary_match.group("files_need_changes"))
        unchanged = int(summary_match.group("unchanged"))
        structurally_broken = int(summary_match.group("broken"))
        not_normalizable = int(summary_match.group("not_normalizable"))

        if files_need_changes > 0:
            blocking_errors.append(
                f"{files_need_changes} file(s) need frontmatter changes"
            )
        if structurally_broken > 0:
            blocking_errors.append(
                f"{structurally_broken} file(s) have structurally broken frontmatter"
            )
    else:
        files_need_changes = -1
        unchanged = -1
        structurally_broken = -1
        not_normalizable = -1
        warnings.append("could not parse summary line from output")

    passed = len(blocking_errors) == 0

    return {
        "passed": passed,
        "files_need_changes": files_need_changes,
        "unchanged": unchanged,
        "structurally_broken": structurally_broken,
        "not_normalizable": not_normalizable,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("adr_frontmatter_drift")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def adr_frontmatter_drift(check: bool, emit_json: bool) -> None:
    """Run the "ADR frontmatter drift" gate.

    Mode: advisory

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      3 = config error
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = 'adr_frontmatter_drift'

    script = repo_root / 'scripts' / 'normalize-adr-frontmatter.sh'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    bash_cmd = ['bash', str(script)]
    try:
        result: Result = run_capture(bash_cmd, gate_name)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

    if emit_json:
        parsed = _parse_adr_frontmatter_drift(result.stdout)
        # gate_fail still fires for blocking errors (::error annotations)
        print(json.dumps(parsed, ensure_ascii=False))
        raise SystemExit(0 if parsed["passed"] else 1)

    # Human-readable: run() already printed stdout, gate_fail already fired
    raise SystemExit(0 if result.returncode == 0 else 1)


register(adr_frontmatter_drift)
