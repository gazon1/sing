"""sgate.commands.adr_frontmatter_drift — shim for: ADR frontmatter drift."""
from __future__ import annotations

from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False


@click.command("adr_frontmatter_drift")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def adr_frontmatter_drift(check: bool, json: bool) -> None:
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

    static_sh = repo_root / 'scripts' / 'ci' / 'static-gates.sh'
    if not static_sh.exists():
        logger.error(f"{static_sh} not found")
        gate_fail(gate_name, None, None, None, None, "static-gates.sh not found")
        raise SystemExit(IO_ERROR)

    bash_cmd = ['bash', '-c', f'. {static_sh} && adr_dry_run']
    try:
        run(bash_cmd, gate_name)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(adr_frontmatter_drift)

