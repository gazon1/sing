"""sgate.commands.check_benchmarks — performance regression checker for gates."""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, IO_ERROR, VALIDATION_FAILURE, register


REGRESSION_THRESHOLD = 3  # 3× warm baseline = failure


@click.command("check-benchmarks")
@click.argument("gate_name", required=False)
@click.option("--gate-name", "gate_name_opt", hidden=True)
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--check", is_flag=True, help="Dry-run: print benchmark results without exiting non-zero")
def check_benchmarks(gate_name: str | None, gate_name_opt: str | None, json: bool, check: bool) -> None:
    """Check gate performance against recorded baselines.

    Runs a gate, measures warm execution time, and fails if it exceeds
    REGRESSION_THRESHOLD × baseline (default 3×).

    Exit codes:
      0 = clean (within baseline)
      1 = validation failure (regression detected)
      2 = I/O error (benchmarks file missing, gate script not found)
      5 = internal error
    """
    name = gate_name_opt or gate_name

    benchmarks_path = sgate.repo.GATE_BENCHMARKS_JSON
    if not benchmarks_path.exists():
        if json:
            click.echo(json.dumps({"error": f"benchmarks file not found: {benchmarks_path}"}))
        else:
            click.echo(f"Benchmarks file not found: {benchmarks_path}", err=True)
        raise SystemExit(IO_ERROR)

    try:
        baselines = json.loads(benchmarks_path.read_text())
    except Exception as exc:
        click.echo(f"Failed to read benchmarks file: {exc}", err=True)
        raise SystemExit(IO_ERROR)

    if name is None:
        # List all benchmarks
        if json:
            click.echo(json.dumps({"benchmarks": baselines}))
        else:
            for gname, rec in baselines.items():
                click.echo(f"  {gname}: {rec.get('warm_ms', '?')} ms")
        raise SystemExit(CLEAN)

    if name not in baselines:
        if json:
            click.echo(json.dumps({"gate": name, "status": "no_baseline", "baseline": None}))
        else:
            click.echo(f"No baseline for '{name}' — run the gate to establish one.", err=True)
        raise SystemExit(IO_ERROR)

    # Run the gate and time it
    gate_cmd = [sys.executable, "-m", "sgate", name]
    # Warm run (discard first to avoid cold-start noise)
    subprocess.run(gate_cmd, capture_output=True, cwd=sgate.repo.ROOT)

    start = time.perf_counter()
    result = subprocess.run(gate_cmd, capture_output=True, cwd=sgate.repo.ROOT)
    elapsed_ms = int((time.perf_counter() - start) * 1000)

    baseline_ms = baselines[name]["warm_ms"]
    threshold_ms = baseline_ms * REGRESSION_THRESHOLD

    if json:
        click.echo(json.dumps({
            "gate": name,
            "warm_ms": elapsed_ms,
            "baseline_ms": baseline_ms,
            "threshold_ms": threshold_ms,
            "regression": elapsed_ms > threshold_ms,
        }))

    if elapsed_ms > threshold_ms:
        click.echo(
            f"REGRESSION: {name} took {elapsed_ms} ms (baseline {baseline_ms} ms, "
            f"threshold {threshold_ms} ms)",
            err=True,
        )
        raise SystemExit(VALIDATION_FAILURE if not check else CLEAN)

    if not json:
        click.echo(f"OK: {name} {elapsed_ms} ms (baseline {baseline_ms} ms)")

    raise SystemExit(CLEAN)


# Register with the CLI group
register(check_benchmarks)
