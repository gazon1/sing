"""sgate.commands.run_all — sgate run-all: run all gates in parallel.

Reads from static-gates.sh registry (same source as ci.yml static job).
Supports: sgate <name> calls, python3 scripts, bash functions, and direct commands.
"""
from __future__ import annotations

import concurrent.futures
import json
import re
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn, TaskProgressColumn

import sgate
from sgate.commands.base import CLEAN, register
from sgate.log import logger


@dataclass
class GateResult:
    """Result of a single gate run."""
    name: str
    mode: str  # "blocking" | "advisory"
    passed: bool
    exit_code: int
    duration_ms: int = 0
    error: str = ""


# Pattern to parse gate lines from static-gates.sh:
#   gate blocking|advisory "name" command...
_GATE_LINE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+)$')


def _load_static_gates() -> list[tuple[str, str, str]]:
    """Load gate registry from static-gates.sh.

    Returns list of (mode, display_name, command).
    Multi-command bash functions are stored as their function name.
    """
    static_sh = sgate.repo.STATIC_GATES_SH
    content = static_sh.read_text(encoding="utf-8")
    gates: list[tuple[str, str, str]] = []

    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "gate()" in stripped:
            continue
        m = _GATE_LINE.match(line)
        if not m:
            continue
        mode, name, cmd = m.group(1), m.group(2), m.group(3).rstrip()
        # Strip inline comment
        if " # " in cmd:
            cmd = cmd.split(" # ", 1)[0].rstrip()
        gates.append((mode, name, cmd))
    return gates


def _slug(name: str) -> str:
    """Convert display name to slug (matches static-gates.sh convention)."""
    slug = name.lower()
    slug = re.sub(r"[^a-z0-9]+", "_", slug)
    slug = slug.strip("_")
    return slug


def _run_gate_subprocess(mode: str, display_name: str, command: str) -> GateResult:
    """Run a single gate command and return structured result."""
    start = time.monotonic()
    repo_root = str(sgate.repo.ROOT)
    static_sh = str(sgate.repo.STATIC_GATES_SH)

    try:
        if command.startswith("sgate "):
            # Delegated to sgate CLI (custom gate already migrated)
            sub_name = command[6:].strip()
            # Discover sgate binary: use the one on PATH, or the venv one in scripts/src
            sgate_bin = shutil.which("sgate")
            if sgate_bin is None:
                # Fallback: find sgate in the venv used by this script
                venv_python = sys.executable
                sgate_bin = str(Path(venv_python).parent / "sgate")
            cmd = [sgate_bin, sub_name, "--json"]
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        elif command.startswith("python3 "):
            # Direct python3 script — use the sgate venv Python (has all dependencies)
            script_cmd = command[7:].strip().split()
            script_path = repo_root + "/" + script_cmd[0]
            args = script_cmd[1:]
            # Discover sgate Python: same Python that runs this script
            venv_python = Path(sys.executable)
            py_bin = str(venv_python.parent / "python")
            cmd = [py_bin, script_path] + args
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        elif command.startswith("./"):
            # Direct shell script
            script_cmd = command[2:].strip().split()
            script_path = repo_root + "/" + script_cmd[0]
            args = script_cmd[1:]
            cmd = ["bash", script_path] + args
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        elif command in ("traceability", "digest_and_sizes", "digest_no_double_suffix",
                         "adr_dry_run"):
            # Bash function defined in static-gates.sh
            # Source the file (with STATIC_GATES_SOURCED=1 so main() is skipped),
            # then call only the requested function.
            bash_cmd = f'STATIC_GATES_SOURCED=1 . "{static_sh}" && {command}'
            cmd = ["bash", "-c", bash_cmd]
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        elif " " in command and not command.startswith("/"):
            # Likely a PATH command (e.g. "singularity-adr validate")
            cmd = command.split()
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        else:
            # Literal path command
            cmd = command.split()
            result = subprocess.run(cmd, capture_output=True, timeout=300,
                                   cwd=repo_root)

        duration_ms = int((time.monotonic() - start) * 1000)
        rc = result.returncode
        # Exit 4 = kiwi unavailable (skip), treated as passed
        passed = rc in (CLEAN, 4)

        return GateResult(
            name=display_name,
            mode=mode,
            passed=passed,
            exit_code=rc,
            duration_ms=duration_ms,
        )

    except subprocess.TimeoutExpired:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=124,
            duration_ms=int((time.monotonic() - start) * 1000),
            error="timeout (>300s)",
        )
    except FileNotFoundError:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=127,
            duration_ms=int((time.monotonic() - start) * 1000),
            error="command not found",
        )
    except Exception as e:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=128,
            duration_ms=int((time.monotonic() - start) * 1000),
            error=str(e),
        )


@click.command("run-all")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--concurrency", "-j", default=8, show_default=True, help="Number of parallel workers")
@click.option("--gate", "gate_filters", multiple=True,
              help="Run only gates whose name contains any of these substrings")
def run_all(emit_json: bool, concurrency: int,
            gate_filters: tuple[str, ...]) -> None:
    """Run all gates from static-gates.sh registry in parallel.

    Exit codes:
      0 = all blocking gates passed
      1 = one or more blocking gates failed
    """
    gates = _load_static_gates()

    if gate_filters:
        gates = [
            (mode, name, cmd)
            for mode, name, cmd in gates
            if any(f.lower() in name.lower() for f in gate_filters)
        ]

    if not gates:
        if emit_json:
            click.echo(json.dumps({"error": "no gates found", "gates": []}))
        else:
            click.echo("[yellow]No gates found.[/yellow]")
        raise SystemExit(CLEAN)

    results: list[GateResult] = []
    results_lock = threading.Lock()

    def worker(mode: str, name: str, cmd: str) -> GateResult:
        r = _run_gate_subprocess(mode, name, cmd)
        with results_lock:
            results.append(r)
        return r

    console = Console()

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
        transient=True,
    ) as progress:
        task = progress.add_task(f"[cyan]Running {len(gates)} gates...", total=len(gates))

        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = {
                executor.submit(worker, mode, name, cmd): (name, cmd)
                for mode, name, cmd in gates
            }
            for future in concurrent.futures.as_completed(futures):
                _ = future.result()
                progress.advance(task)

    # Sort: blocking first, then advisory; alphabetically within group
    order = {"blocking": 0, "advisory": 1}
    results.sort(key=lambda r: (order.get(r.mode, 2), r.name))

    blocking = [r for r in results if r.mode == "blocking"]
    advisory = [r for r in results if r.mode == "advisory"]
    blocking_failed = [r for r in blocking if not r.passed]
    overall_ok = all(r.passed for r in blocking)

    if emit_json:
        out = {
            "total": len(results),
            "blocking": {"total": len(blocking), "passed": sum(1 for r in blocking if r.passed)},
            "advisory": {"total": len(advisory), "passed": sum(1 for r in advisory if r.passed)},
            "blocking_failed": [r.name for r in blocking_failed],
            "advisory_failed": [r.name for r in advisory if not r.passed],
            "passed": overall_ok,
            "gates": [
                {
                    "name": r.name,
                    "mode": r.mode,
                    "passed": r.passed,
                    "exit_code": r.exit_code,
                    "duration_ms": r.duration_ms,
                    "error": r.error,
                }
                for r in results
            ],
        }
        click.echo(json.dumps(out, indent=2))
        raise SystemExit(0 if overall_ok else 1)

    # Human-readable
    from rich.panel import Panel
    from rich.table import Table

    if overall_ok:
        summary = f"[green]✓ All {len(blocking)} blocking gates passed[/green]"
    else:
        names = ", ".join(r.name for r in blocking_failed)
        summary = f"[red]✗ {len(blocking_failed)}/{len(blocking)} blocking failed: {names}[/red]"
    console.print(Panel(summary, title="[bold]sgate run-all[/bold]"))

    def make_table(title: str, rows: list[GateResult], color: str) -> Table:
        t = Table(title=title, show_header=True)
        t.add_column("Gate", style=color)
        t.add_column("Status", width=14)
        t.add_column("Duration", justify="right", style="dim")
        for r in rows:
            if r.passed:
                status = f"[green]✓ pass[/green]"
            elif r.exit_code == 127:
                status = "[red]✗ miss[/red]"
            elif r.exit_code == 124:
                status = "[red]✗ timeout[/red]"
            else:
                status = f"[red]✗ exit={r.exit_code}[/red]"
            t.add_row(r.name, status, f"{r.duration_ms}ms")
        return t

    if blocking:
        console.print(make_table(
            f"Blocking Gates ({sum(1 for r in blocking if r.passed)}/{len(blocking)} passed)",
            blocking, "cyan",
        ))
    if advisory:
        console.print(make_table(
            f"Advisory Gates ({sum(1 for r in advisory if r.passed)}/{len(advisory)} passed)",
            advisory, "yellow",
        ))

    raise SystemExit(0 if overall_ok else 1)


register(run_all)
