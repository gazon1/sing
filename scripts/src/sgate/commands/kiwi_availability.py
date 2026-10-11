"""sgate.commands.kiwi_availability — hard gate: Kiwi TCMS must be available."""
from __future__ import annotations

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, KIWI_UNAVAILABLE, register
from sgate.kiwi import KiwiUnavailable


@click.command("kiwi-availability")
@click.option("--check", is_flag=True, help="Dry-run: print Kiwi status without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def kiwi_availability(check: bool, json: bool) -> None:
    """Hard gate: fail if Kiwi TCMS is unreachable.

    This is the only gate with KIWI_REQUIRED=True by definition. All other
    Kiwi-dependent gates skip silently and let this gate report the failure.

    Exit codes:
      0 = Kiwi TCMS is available and responsive
      4 = Kiwi TCMS is unavailable or unreachable
      5 = internal error (unexpected exception)
    """
    try:
        from sgate.kiwi import load_scanner
        result = load_scanner()
        if json:
            import json as _json
            click.echo(_json.dumps({"kiwi": "available", "scanner": str(type(result.scanner))}))
        else:
            click.echo("Kiwi TCMS is available.")
        raise SystemExit(CLEAN)
    except KiwiUnavailable as exc:
        if json:
            import json as _json
            click.echo(_json.dumps({"kiwi": "unavailable", "reason": exc.msg}))
        else:
            click.echo(f"Kiwi TCMS unavailable: {exc.msg}", err=True)
        raise SystemExit(KIWI_UNAVAILABLE)
    except Exception as exc:
        if json:
            import json as _json
            click.echo(_json.dumps({"kiwi": "error", "reason": str(exc)}))
        else:
            click.echo(f"Unexpected error: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)


# Register with the CLI group
register(kiwi_availability)
