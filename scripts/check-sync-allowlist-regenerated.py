#!/usr/bin/env python3
"""
Gate: fails if supabase/migrations/sync_field_allowlist_seed.sql differs from
what would be generated from SyncContract.FIELD_ALLOWLIST today.

## Migration note
Migrated 2026-10-17 from argparse to Click (R2.1 of check-script-conventions.py).
Uses sgate.commands.base.CLEAN and VALIDATION_FAILURE for exit codes.

Run standalone: python3 scripts/check-sync-allowlist-regenerated.py
Run self-test:  python3 scripts/check-sync-allowlist-regenerated.py --self-test
"""
from __future__ import annotations

import os
import subprocess
import sys

import click
from sgate.commands.base import run

from sgate.commands.base import CLEAN, VALIDATION_FAILURE

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)  # project root
GEN_SCRIPT = os.path.join(ROOT, "infra/gen_sync_allowlist_sql.py")
OUTPUT_FILE = os.path.join(ROOT, "supabase/migrations/sync_field_allowlist_seed.sql")


@click.command()
@click.option(
    "--self-test",
    is_flag=True,
    help="Assert that the gate correctly fails when the seed is stale",
)
@click.pass_context
def cli(ctx: click.Context, self_test: bool) -> None:
    """Check that sync_field_allowlist_seed.sql matches what gen_sync_allowlist_sql.py would generate."""
    # Read current committed file
    with open(OUTPUT_FILE) as f:
        before = f.read()

    if self_test:
        # Mutate the seed to make it stale: add a field that does not exist in the
        # generator's output. The gate will see a diff and must exit non-zero.
        with open(OUTPUT_FILE, "w") as f:
            f.write(before + '    --self-test-probe-field text,\n')

    # Regenerate
    result = subprocess.run(
        ["python3", GEN_SCRIPT, ROOT],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        click.echo(f"FAIL: generator script exited {result.returncode}")
        click.echo(result.stderr, err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    with open(OUTPUT_FILE) as f:
        after = f.read()

    # Restore original content so the self-test leaves no trace
    with open(OUTPUT_FILE, "w") as f:
        f.write(before)

    if before != after:
        click.echo("FAIL: sync_field_allowlist_seed.sql is stale — run:")
        click.echo(f"  python3 {GEN_SCRIPT} {ROOT}")
        click.echo(f"  then commit the change")
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo("PASS: sync_field_allowlist_seed.sql is up to date with SyncContract.FIELD_ALLOWLIST")
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
